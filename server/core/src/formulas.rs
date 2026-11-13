//! Game math ported from the client (via `reference-server/sgserver/formulas.py`).
//!
//! The client computes in float32; using Rust `f32` throughout reproduces its rounding exactly, so
//! levels, stats and fighter scores match what the game shows. `GameSettings` values come from the
//! content JSON (f64) and are narrowed to f32 at the edge, matching the Python port.

use crate::content::Content;
use serde_json::Value;
use std::collections::HashMap;

pub const MAX_CHARACTER_LEVELS: [i64; 4] = [30, 40, 50, 60];

pub fn level_cap(tier: i64) -> i64 {
    let i = tier.clamp(0, (MAX_CHARACTER_LEVELS.len() - 1) as i64);
    MAX_CHARACTER_LEVELS[i as usize]
}

/// Math.Round(x, AwayFromZero) as used by the client, returning an integer.
pub fn round_away(x: f32) -> i64 {
    let r = (x.abs() + 0.5).floor() as i64;
    if x >= 0.0 {
        r
    } else {
        -r
    }
}

/// CharacterCalcUtil.GetHalvedValueCeiling (shiny characters need half the XP).
pub fn ceil_half(v: i64) -> i64 {
    if v >= 0 {
        (v + 1) / 2
    } else {
        -((-v) / 2)
    }
}

fn gf(v: &Value, key: &str) -> f32 {
    v.get(key).and_then(|x| x.as_f64()).unwrap_or(0.0) as f32
}
fn gi(v: &Value, key: &str) -> i64 {
    v.get(key).and_then(|x| x.as_i64()).unwrap_or(0)
}
fn garr_f(v: &Value, key: &str) -> Vec<f32> {
    v.get(key)
        .and_then(|x| x.as_array())
        .map(|a| a.iter().map(|e| e.as_f64().unwrap_or(0.0) as f32).collect())
        .unwrap_or_default()
}

/// CharacterCalcUtil.IterateLevelLadder: cumulative XP to reach each level.
pub struct Ladder {
    pub cap: i64,
    totals: Vec<i64>, // index by level; totals[1]=0..totals[cap]
}

impl Ladder {
    pub fn new(base: f32, mult: f32, thresholds: &[(i64, f32)], cap: i64) -> Ladder {
        let mut totals = vec![0i64; (cap + 1) as usize];
        totals[1] = 0;
        let mut inc = base as i64;
        let mut total = base as i64;
        if cap >= 2 {
            totals[2] = total;
        }
        let m = mult;
        let fbase = base;
        let mut thr: HashMap<i64, f32> = HashMap::new();
        for (lvl, mul) in thresholds {
            thr.insert(*lvl, *mul); // later entries win, like the client loop
        }
        for level in 3..=cap {
            let v = if let Some(tm) = thr.get(&level) {
                *tm * fbase
            } else {
                m * (inc as f32)
            };
            inc = round_away(v);
            total += inc;
            totals[level as usize] = total;
        }
        Ladder { cap, totals }
    }

    pub fn xp_for_level(&self, level: i64, shiny: bool) -> i64 {
        let level = level.clamp(1, self.cap);
        let v = self.totals[level as usize];
        if shiny {
            ceil_half(v)
        } else {
            v
        }
    }
    pub fn level_for_xp(&self, xp: i64, shiny: bool) -> i64 {
        let mut lvl = 1;
        for level in 2..=self.cap {
            if xp >= self.xp_for_level(level, shiny) {
                lvl = level;
            } else {
                break;
            }
        }
        lvl
    }
    pub fn float_level(&self, xp: i64, shiny: bool) -> f64 {
        let lvl = self.level_for_xp(xp, shiny);
        if lvl >= self.cap {
            return self.cap as f64;
        }
        let lo = self.xp_for_level(lvl, shiny);
        let hi = self.xp_for_level(lvl + 1, shiny);
        if hi > lo {
            lvl as f64 + (xp - lo) as f64 / (hi - lo) as f64
        } else {
            lvl as f64
        }
    }
}

pub struct Formulas<'c> {
    c: &'c Content,
    pub player_ladder: Ladder,
    char_ladders: HashMap<i64, Ladder>, // precomputed for every tier, so the type is immutable + Sync
}

impl<'c> Formulas<'c> {
    pub fn new(c: &'c Content) -> Formulas<'c> {
        let gs = c.game_settings();
        let thresholds = thresholds_of(gs, "playerXpResetThresholds");
        let mult = gf(gs, "nextLevelPlayerXpMultiplier") * gf(gs, "playerLevelingMultiplier");
        let player_ladder = Ladder::new(gf(gs, "baseLevelUpPlayerXp"), mult, &thresholds, 100);
        let mut char_ladders = HashMap::new();
        let per_tier = garr_f(gs, "levelingMultiplierPerTier");
        let bases = garr_f(gs, "baseLevelUpXpPerTier");
        let thr = thresholds_of(gs, "xpResetThresholds");
        for tier in 0..MAX_CHARACTER_LEVELS.len() as i64 {
            let mult = gf(gs, "nextLevelXpMultiplier") * per_tier.get(tier as usize).copied().unwrap_or(1.0);
            let base = bases.get(tier as usize).copied().unwrap_or(0.0);
            char_ladders.insert(tier, Ladder::new(base, mult, &thr, level_cap(tier)));
        }
        Formulas { c, player_ladder, char_ladders }
    }

    pub fn char_ladder(&self, tier: i64) -> &Ladder {
        let t = tier.clamp(0, MAX_CHARACTER_LEVELS.len() as i64 - 1);
        self.char_ladders.get(&t).expect("char ladders precomputed for every tier")
    }

    pub fn base_values(&self, variant: &Value, tier: i64) -> (f32, f32) {
        let vals = variant.get("baseScaledValuesByTier").and_then(|v| v.as_array());
        let initial = gi(variant, "initialTier");
        match vals {
            Some(a) if !a.is_empty() => {
                let i = (tier - initial).clamp(0, (a.len() - 1) as i64) as usize;
                let v = &a[i];
                (gf(v, "lifebar"), gf(v, "attack"))
            }
            _ => (100.0, 10.0),
        }
    }

    pub fn lifebar(&self, variant: &Value, tier: i64, level: i64, buff: f32) -> i64 {
        let (base, _) = self.base_values(variant, tier);
        let gs = self.c.game_settings();
        let fb = base;
        let v = ((level - 1) as f32 * fb) * gf(gs, "levelScaleForLifebar");
        let v = v + fb;
        (v * (buff + 1.0)).ceil() as i64
    }
    pub fn attack(&self, variant: &Value, tier: i64, level: i64, buff: f32) -> i64 {
        let (_, base) = self.base_values(variant, tier);
        let gs = self.c.game_settings();
        let fb = base;
        let v = ((level - 1) as f32 * fb) * gf(gs, "levelScaleForAttack");
        let v = v + fb;
        (v * (buff + 1.0)).ceil() as i64
    }

    pub fn fighter_score(
        &self,
        lifebar: i64,
        attack: i64,
        fs_mult_addition: f32,
        super_level: i64,
        prestige_level: i64,
    ) -> i64 {
        let gs = self.c.game_settings();
        let base = (lifebar as f32 / 6.0) + attack as f32;
        let mut mult = fs_mult_addition;
        if super_level > 0 {
            mult = (mult + gf(gs, "superAbilityUnlockFSMultiplierAddition"))
                + gf(gs, "superAbilityFSMultiplierAdditionPerLevel") * super_level as f32;
        }
        if prestige_level > 0 {
            if prestige_level >= 100 {
                mult += gf(gs, "prestigeAbilityMaxFSMultiplierAddition");
            } else {
                mult = (mult + gf(gs, "prestigeAbilityUnlockFSMultiplierAddition"))
                    + gf(gs, "prestigeAbilityFSMultiplierPerLevel") * prestige_level as f32;
            }
        }
        ((base + base * mult) * gf(gs, "overallFighterScoreMultiplier")).ceil() as i64
    }

    pub fn longshot_multiplier(player_fs: i64, enemy_fs: i64) -> f64 {
        if enemy_fs <= 0 || player_fs >= enemy_fs {
            return 1.0;
        }
        let v = ((1.0f32 - (player_fs as f32 / enemy_fs as f32)) + 1.0).min(2.0);
        ((v * 10.0).floor() / 10.0) as f64
    }
}

fn thresholds_of(gs: &Value, key: &str) -> Vec<(i64, f32)> {
    gs.get(key)
        .and_then(|v| v.as_array())
        .map(|a| {
            a.iter()
                .map(|t| {
                    (
                        t.get("level").and_then(|v| v.as_i64()).unwrap_or(0),
                        t.get("baseLevelUpXpMultiplier").and_then(|v| v.as_f64()).unwrap_or(0.0) as f32,
                    )
                })
                .collect()
        })
        .unwrap_or_default()
}

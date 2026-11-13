//! Player document and its serialization to the client's *ServerData shapes.
//!
//! Ported from `reference-server/sgserver/player.py`. The persisted document is a JSON object (the
//! same shape the Python server stores), so saves are interchangeable between the two servers.

use crate::content::Content;
use crate::formulas::{level_cap, Formulas};
use crate::rng::Rng;
use serde_json::{json, Map, Value};

pub const OTHER_CURRENCIES: &[&str] = &[
    "BronzeKey", "SilverKey", "GoldKey", "DiamondKey", "SilverGachaShard", "GoldGachaShard",
    "DiamondGachaShard", "CharacterGachaShard", "BattleCurrency", "BattleTicketFree",
    "BattleTicketPaid", "DailyEventTicket", "EnergyRefill", "UniversalRerollRune",
    "AssistEvoCurrency", "SocialGiftStandard", "SocialGiftGold", "SocialGiftStandardToOpen",
    "SocialGiftGoldToOpen", "GuildGiftStandard", "GuildGiftPremium", "GuildGiftStandardToOpen",
    "GuildGiftPremiumToOpen", "RaidBossTicketFree", "RaidBossTicketPaid", "FireEssence",
    "WaterEssence", "AirEssence", "LightEssence", "DarkEssence", "FireEssenceShard",
    "WaterEssenceShard", "AirEssenceShard", "LightEssenceShard", "DarkEssenceShard",
];

// (bit, cleared-progress) per TutorialType
const TUTORIALS: &[(&str, i64, i64)] = &[
    ("FTUE", 1, 8), ("MoveEquip", 2, 1), ("SkillTree", 4, 1), ("RiftBattle", 8, 5),
    ("XPBoost", 16, 1), ("Maze", 32, 2), ("Pity", 64, 1), ("RaidBoss", 128, 1),
    ("AutoBattle", 256, 1), ("PrizeFight", 512, 2), ("MissionControl", 1024, 2),
];

pub struct PlayerService<'c> {
    pub c: &'c Content,
    pub f: Formulas<'c>,
    pub cfg: crate::config::Config,
    pub max_energy: i64,
}

fn obj(v: &mut Value) -> &mut Map<String, Value> {
    v.as_object_mut().expect("player document must be an object")
}

impl<'c> PlayerService<'c> {
    pub fn new(c: &'c Content, f: Formulas<'c>, cfg: crate::config::Config) -> Self {
        PlayerService { c, f, cfg, max_energy: 10 }
    }

    // ---- creation ----------------------------------------------------------------------
    pub fn create(&self, rng: &mut Rng, device_id: Option<&str>) -> Value {
        let uid = rng.hex32();
        let mut user = json!({
            "id": uid,
            "publicId": rng.range(100_000_000, 1_000_000_000).to_string(),
            "handle": format!("Player{}", rng.range(1000, 10000)),
            "created": crate::http::now_secs() as i64 * 1000,
            "xp": 0,
            "currencies": self.cfg.starting_currencies(),
            "skillPoints": {}, "characters": {}, "moves": {}, "assistMoves": {},
            "nodeModifiers": {}, "consumables": {}, "gachas": {}, "rerollRunes": {},
            "team": [], "variantsEverOwned": {},
            "tutorial": {"progress": 0, "completed": 0, "map": {}},
            "campaign": {}, "fights": {}, "avatar": "",
            "deviceIds": device_id.map(|d| vec![d.to_string()]).unwrap_or_default(),
        });

        // starting loot from EconomySettings
        if let Some(loot) = self.c.economy().get("startingLoot").and_then(|v| v.as_array()).cloned() {
            for l in &loot {
                crate::rewards::grant_loot(self, &mut user, l, 1, rng);
            }
        }
        if self.cfg.bool("skip_tutorials", true) {
            self.complete_all_tutorials(&mut user);
        }
        // starting team from TutorialManager.finalTeamCompositionV2
        let mut starters: Vec<String> = Vec::new();
        if let Some(tm) = self.c.single("TutorialManager") {
            if let Some(comp) = tm.get("finalTeamCompositionV2").and_then(|v| v.as_array()) {
                for gen in comp {
                    let cd = gen.get("characterData").and_then(|v| v.as_str()).unwrap_or("");
                    if let Some(v) = self.c.get(cd) {
                        if let Some(g) = v.get("guid").and_then(|v| v.as_str()) {
                            starters.push(g.to_string());
                        }
                    }
                }
            }
        }
        if let Some(extra) = self.cfg.get("extra_starting_characters").and_then(|v| v.as_array()) {
            for g in extra {
                if let Some(s) = g.as_str() {
                    starters.push(s.to_string());
                }
            }
        }
        for guid in starters {
            if let Some(cid) = self.grant_character(&mut user, rng, &guid, None, 0, false) {
                let team_len = user["team"].as_array().map(|a| a.len()).unwrap_or(0);
                if team_len < 3 {
                    user["team"].as_array_mut().unwrap().push(Value::String(cid));
                }
            }
        }
        let first = user["team"].as_array().and_then(|a| a.first()).and_then(|v| v.as_str()).map(String::from);
        if let Some(cid) = first {
            if let Some(variant) = user["characters"][&cid].get("variant").cloned() {
                user["avatar"] = variant;
            }
        }
        user
    }

    pub fn complete_all_tutorials(&self, user: &mut Value) {
        let t = user.get_mut("tutorial").unwrap();
        let mut completed = t["completed"].as_i64().unwrap_or(0);
        let map = t.get_mut("map").unwrap().as_object_mut().unwrap();
        for (name, bit, cleared) in TUTORIALS {
            completed |= bit;
            map.insert((*name).into(), json!(cleared));
        }
        t["completed"] = json!(completed);
        t["progress"] = json!(8); // FTUE cleared
    }

    // ---- inventory ---------------------------------------------------------------------
    pub fn add_currency(&self, user: &mut Value, name: &str, amount: i64) {
        let cur = obj(user.get_mut("currencies").unwrap());
        let now = cur.get(name).and_then(|v| v.as_i64()).unwrap_or(0);
        cur.insert(name.into(), json!((now + amount).max(0)));
    }

    pub fn grant_character(
        &self,
        user: &mut Value,
        rng: &mut Rng,
        variant_guid: &str,
        tier: Option<i64>,
        xp: i64,
        shiny: bool,
    ) -> Option<String> {
        let v = self.c.guid(variant_guid)?;
        if v.get("_class").and_then(|x| x.as_str()) != Some("VariantCharacterData") {
            return None;
        }
        let initial_tier = v.get("initialTier").and_then(|x| x.as_i64()).unwrap_or(0);
        let starting_gear = v.get("startingGear").and_then(|x| x.as_str()).unwrap_or("").to_string();
        let base_guid = v
            .get("baseCharacter")
            .and_then(|x| x.as_str())
            .and_then(|r| self.c.get(r))
            .and_then(|b| b.get("guid"))
            .and_then(|g| g.as_str())
            .map(String::from);
        let cid = rng.hex32();
        let mut moves: Vec<Value> = Vec::new();
        if !starting_gear.is_empty() {
            if let Some(g) = self.c.get(&starting_gear).and_then(|x| x.get("guid")).and_then(|x| x.as_str()) {
                let mid = self.grant_move(user, rng, g, false, "moves");
                moves.push(Value::String(mid));
            }
        }
        let ch = json!({
            "id": cid, "variant": variant_guid, "tier": tier.unwrap_or(initial_tier),
            "xp": xp, "shiny": shiny, "nodes": [], "moves": moves, "assist": "",
            "energy": self.max_energy, "energyTs": 0, "locked": false, "new": true,
            "superLevels": [], "superFeature": 0, "prestige": 0,
            "created": crate::http::now_secs() as i64 * 1000,
        });
        obj(user.get_mut("characters").unwrap()).insert(cid.clone(), ch);
        if let Some(base) = base_guid {
            let ever = obj(user.get_mut("variantsEverOwned").unwrap());
            let entry = ever.entry(base).or_insert_with(|| json!({"normal": [], "shiny": []}));
            let list = entry.get_mut(if shiny { "shiny" } else { "normal" }).unwrap().as_array_mut().unwrap();
            if !list.iter().any(|x| x.as_str() == Some(variant_guid)) {
                list.push(Value::String(variant_guid.into()));
            }
        }
        Some(cid)
    }

    pub fn grant_move(&self, user: &mut Value, rng: &mut Rng, guid: &str, new: bool, pool: &str) -> String {
        let mid = rng.hex32();
        let mv = json!({"id": mid, "guid": guid, "evolution": 1, "stats": [], "locked": false, "new": new});
        obj(user.get_mut(pool).unwrap()).insert(mid.clone(), mv);
        mid
    }

    // ---- derived -----------------------------------------------------------------------
    pub fn level(&self, user: &Value) -> i64 {
        let xp = user.get("xp").and_then(|v| v.as_i64()).unwrap_or(0);
        self.f.player_ladder.level_for_xp(xp, false)
    }

    pub fn char_stats(&self, ch: &Value) -> (i64, i64, i64, i64) {
        let variant_guid = ch.get("variant").and_then(|v| v.as_str()).unwrap_or("");
        let v = self.c.guid(variant_guid).cloned().unwrap_or(json!({}));
        let tier = ch.get("tier").and_then(|x| x.as_i64()).unwrap_or(0);
        let shiny = ch.get("shiny").and_then(|x| x.as_bool()).unwrap_or(false);
        let xp = ch.get("xp").and_then(|x| x.as_i64()).unwrap_or(0);
        let nodes: Vec<String> = ch.get("nodes").and_then(|x| x.as_array())
            .map(|a| a.iter().filter_map(|n| n.as_str().map(String::from)).collect()).unwrap_or_default();
        let (lb_buff, atk_buff, fs_add) = crate::skilltree::skill_buffs(self.c, &nodes);
        let lvl = self.f.char_ladder(tier).level_for_xp(xp, shiny);
        let lb = self.f.lifebar(&v, tier, lvl, lb_buff);
        let at = self.f.attack(&v, tier, lvl, atk_buff);
        let sup = ch.get("superLevels").and_then(|x| x.as_array())
            .and_then(|a| a.iter().filter_map(|e| e.as_i64()).max()).unwrap_or(0);
        let prestige = ch.get("prestige").and_then(|x| x.as_i64()).unwrap_or(0);
        let fs = self.f.fighter_score(lb, at, fs_add, sup, prestige);
        (lvl, lb, at, fs)
    }

    pub fn skill_points_per_level(&self, tier: i64) -> i64 {
        let econ = self.c.economy();
        let mults = econ.get("skillPointTierMultipliers").and_then(|v| v.as_array());
        let m = mults.and_then(|a| a.get((tier as usize).min(a.len().saturating_sub(1)))).and_then(|v| v.as_i64()).unwrap_or(1);
        econ.get("skillPointsOnCharacterLevelUp").and_then(|v| v.as_i64()).unwrap_or(1) * m
    }

    // ---- serialization -----------------------------------------------------------------
    pub fn character_data(&self, user: &Value, ch: &mut Value) -> Value {
        self.refresh_energy(ch);
        let (lvl, lb, at, _) = self.char_stats(ch);
        let tier = ch.get("tier").and_then(|x| x.as_i64()).unwrap_or(0);
        let shiny = ch.get("shiny").and_then(|x| x.as_bool()).unwrap_or(false);
        let xp = ch.get("xp").and_then(|x| x.as_i64()).unwrap_or(0);
        let flt = self.f.char_ladder(tier).float_level(xp, shiny);
        let moves: Vec<Value> = ch.get("moves").and_then(|x| x.as_array()).map(|a| {
            a.iter().filter(|m| {
                m.as_str().map(|s| user["moves"].get(s).is_some()).unwrap_or(false)
            }).cloned().collect()
        }).unwrap_or_default();
        let assist = ch.get("assist").and_then(|x| x.as_str()).filter(|s| !s.is_empty());
        json!({
            "id": ch["id"], "variantGuid": ch["variant"], "tier": tier,
            "experience": xp, "floatLevel": flt, "level": lvl,
            "skillNodeGuids": ch.get("nodes").cloned().unwrap_or(json!([])),
            "superAbilityLevels": ch.get("superLevels").cloned().unwrap_or(json!([])),
            "superAbilityActiveFeatureSet": ch.get("superFeature").cloned().unwrap_or(json!(0)),
            "prestigeAbilityLevel": ch.get("prestige").cloned().unwrap_or(json!(0)),
            "energy": ch["energy"], "nextEnergyTimestamp": ch.get("energyTs").cloned().unwrap_or(json!(0)),
            "energyMp": self.max_energy, "nextEnergyTimeMpStamp": 0,
            "lifebar": lb, "attack": at, "specialMoveIds": moves,
            "assistMoveInstanceId": assist.map(Value::from).unwrap_or(Value::Null),
            "isLocked": ch.get("locked").cloned().unwrap_or(json!(false)),
            "isNew": ch.get("new").cloned().unwrap_or(json!(false)),
            "isShiny": shiny, "isUsedInDeployment": false,
        })
    }

    pub fn move_data(&self, mv: &Value) -> Value {
        let guid = mv.get("guid").and_then(|v| v.as_str()).unwrap_or("");
        let g = self.c.guid(guid).cloned().unwrap_or(json!({}));
        json!({
            "id": mv["id"], "moveGuid": mv["guid"],
            "evolution": mv.get("evolution").cloned().unwrap_or(json!(1)),
            "saleValue": {"amounts": {"SoftCurrency": self.move_sale_value(&g)}},
            "moveStatInstances": mv.get("stats").and_then(|s| s.as_array()).map(|a| {
                a.iter().map(|s| json!({"guid": s.get("guid").cloned().unwrap_or(Value::Null),
                                        "level": s.get("level").cloned().unwrap_or(json!(1))})).collect::<Vec<_>>()
            }).unwrap_or_default(),
            "isLocked": mv.get("locked").cloned().unwrap_or(json!(false)),
            "isNew": mv.get("new").cloned().unwrap_or(json!(false)),
        })
    }

    fn move_sale_value(&self, gear: &Value) -> i64 {
        let econ = self.c.economy();
        let prices = econ.get("baseGearSalePricePerTier").and_then(|v| v.as_array());
        let tier = gear.get("tier").and_then(|v| v.as_i64()).unwrap_or(0);
        let p = prices.and_then(|a| a.get((tier.max(0) as usize).min(a.len().saturating_sub(1)))).and_then(|v| v.as_f64()).unwrap_or(0.0);
        (p * econ.get("gearSalePercent").and_then(|v| v.as_f64()).unwrap_or(1.0)) as i64
    }

    pub fn refresh_energy(&self, ch: &mut Value) {
        let now = crate::http::now_secs() as i64 * 1000;
        let cooldown = self.c.game_settings().get("energyCooldownSeconds").and_then(|v| v.as_i64()).unwrap_or(600) * 1000;
        let mut energy = ch.get("energy").and_then(|v| v.as_i64()).unwrap_or(self.max_energy);
        let mut ts = ch.get("energyTs").and_then(|v| v.as_i64()).unwrap_or(0);
        if energy >= self.max_energy {
            ch["energyTs"] = json!(0);
            return;
        }
        if ts == 0 {
            ts = now + cooldown;
        }
        while energy < self.max_energy && now >= ts {
            energy += 1;
            ts = if energy < self.max_energy { ts + cooldown } else { 0 };
        }
        ch["energy"] = json!(energy);
        ch["energyTs"] = json!(ts);
    }

    pub fn currency_balance(&self, amount: i64) -> Value {
        json!({"paid": 0, "free": amount, "bonus": 0})
    }

    pub fn user_data(&self, user: &mut Value, full: bool) -> Value {
        let cur = user.get("currencies").cloned().unwrap_or(json!({}));
        let curget = |k: &str| cur.get(k).and_then(|v| v.as_i64()).unwrap_or(0);
        let lvl = self.level(user);
        let xp = user.get("xp").and_then(|v| v.as_i64()).unwrap_or(0);
        let flt = self.f.player_ladder.float_level(xp, false);
        let t = user.get("tutorial").cloned().unwrap_or(json!({}));
        let other: Vec<Value> = OTHER_CURRENCIES.iter().filter_map(|k| {
            let n = curget(k);
            if n != 0 { Some(json!({"type": k, "amount": n})) } else { None }
        }).collect();
        let map_list = |m: &Value, kf: &str, vf: &str| -> Vec<Value> {
            m.as_object().map(|o| o.iter().map(|(k, v)| json!({kf: k, vf: v})).collect()).unwrap_or_default()
        };
        let items_list = |m: &Value| -> Vec<Value> {
            m.as_object().map(|o| o.iter().filter(|(_, v)| v.as_i64().unwrap_or(0) > 0)
                .map(|(k, v)| json!({"guid": k, "count": v})).collect()).unwrap_or_default()
        };
        let cap = self.cfg.i64("collection_capacity", 1000);
        let count = |k: &str| user.get(k).and_then(|v| v.as_object()).map(|o| o.len()).unwrap_or(0);
        let team: Vec<Value> = user.get("team").and_then(|v| v.as_array()).map(|a| {
            a.iter().filter(|cid| cid.as_str().map(|s| user["characters"].get(s).is_some()).unwrap_or(false)).cloned().collect()
        }).unwrap_or_default();
        let ever: Vec<Value> = user.get("variantsEverOwned").and_then(|v| v.as_object()).map(|o| {
            o.iter().map(|(g, e)| json!({
                "guid": g, "variantsEverOwned": e.get("normal").cloned().unwrap_or(json!([])),
                "variantsEverOwnedShiny": e.get("shiny").cloned().unwrap_or(json!([])),
            })).collect()
        }).unwrap_or_default();

        let mut out = json!({
            "id": user["id"], "publicId": user["publicId"], "publicUserId": user["publicId"],
            "handle": user["handle"], "hasFreeHandleChange": true, "metricsId": user["id"],
            "level": lvl, "floatLevel": flt, "experience": xp,
            "tutorialProgress": t.get("progress").cloned().unwrap_or(json!(0)),
            "completedTutorials": t.get("completed").cloned().unwrap_or(json!(0)),
            "tutorialProgressMap": t.get("map").cloned().unwrap_or(json!({})),
            "hardCurrency": self.currency_balance(curget("HardCurrency")),
            "softCurrency": self.currency_balance(curget("SoftCurrency")),
            "otherCurrencies": other,
            "skillPoints": map_list(&user["skillPoints"], "guid", "amount"),
            "rerollRunes": map_list(user.get("rerollRunes").unwrap_or(&json!({})), "guid", "amount"),
            "consumables": user.get("consumables").and_then(|v| v.as_object()).map(|o| {
                o.iter().filter(|(_, v)| v.as_i64().unwrap_or(0) > 0)
                 .map(|(k, v)| json!({"guid": k, "count": v, "isNew": false})).collect::<Vec<_>>()
            }).unwrap_or_default(),
            "items": items_list(user.get("gachas").unwrap_or(&json!({}))),
            "teamIds": team, "repCharacterId": user.get("avatar").cloned().unwrap_or(json!("")),
            "tags": [], "adsAvailable": 0, "nextAdGeneratedTimestamp": 0,
            "freeBattleTicketRefreshDate": 0, "dailyEventTicketRefreshDate": 0,
            "dailyRaidBossTicketRefreshDate": 0, "removeFriendsDailyTimestampReset": 0,
            "numberOfFriendsRemoved": 0, "tier": 0, "accounts": [],
            "variantsEverOwned": ever,
            "characterCollectionCapacity": cap, "gearCollectionCapacity": cap,
            "assistMoveCollectionCapacity": cap, "nodeModifierCollectionCapacity": cap,
            "characterCollectionCount": count("characters"), "moveCollectionCount": count("moves"),
            "assistMoveCollectionCount": count("assistMoves"), "nodeModifierCollectionCount": count("nodeModifiers"),
            "newPortraitCount": 0, "eventLockedCharacterIdMap": {},
            "claimableChallengeCount": 0, "claimableChallengeRewardThresholdCount": 0,
            "challengeExpirationDate": 0, "newChallengesAvailable": false,
            "battlePassPoints": 0, "battlePassPremiumActive": false, "battlePassNotificationCount": 0,
            "battlePassAutoClaimedChallenges": [], "adIds": {},
            "topTeamPower": self.top_team_power(user),
            "cosmeticsInstance": {"portraitsOwned": [], "newPortraits": []},
        });
        if full {
            let char_keys: Vec<String> = user["characters"].as_object().map(|o| o.keys().cloned().collect()).unwrap_or_default();
            let mut chars = Vec::new();
            for k in char_keys {
                let mut ch = user["characters"][&k].clone();
                chars.push(self.character_data(user, &mut ch));
                user["characters"][&k] = ch; // keep energy refresh
            }
            out["characters"] = json!(chars);
            out["specialMoves"] = json!(user["moves"].as_object().map(|o| o.values().map(|m| self.move_data(m)).collect::<Vec<_>>()).unwrap_or_default());
            out["assistMoves"] = json!(user["assistMoves"].as_object().map(|o| o.values().map(|m| self.move_data(m)).collect::<Vec<_>>()).unwrap_or_default());
            out["nodeModifiers"] = json!([]);
        }
        out
    }

    pub fn top_team_power(&self, user: &Value) -> i64 {
        let mut scores: Vec<i64> = user.get("characters").and_then(|v| v.as_object()).map(|o| {
            o.values().map(|ch| self.char_stats(ch).3).collect()
        }).unwrap_or_default();
        scores.sort_unstable_by(|a, b| b.cmp(a));
        scores.iter().take(3).sum()
    }

    pub fn feeding_skill_points(&self, food: &Value) -> i64 {
        let tier = food.get("tier").and_then(|v| v.as_i64()).unwrap_or(0);
        let xp = food.get("xp").and_then(|v| v.as_i64()).unwrap_or(0);
        let shiny = food.get("shiny").and_then(|v| v.as_bool()).unwrap_or(false);
        let lvl = self.f.char_ladder(tier).level_for_xp(xp, shiny);
        ((level_cap(tier) - lvl) * self.skill_points_per_level(tier)).max(0)
    }
}

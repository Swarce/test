"""Game math ported from the client (CharacterCalcUtil, VariantCharacterData, ProgressionManager).

The client does this arithmetic in float32; f32() reproduces that so levels, stats and
fighter scores match what the game shows.
"""
import math
import struct

# Character.kMaxCharacterLevels (Bronze, Silver, Gold, Diamond)
MAX_CHARACTER_LEVELS = [30, 40, 50, 60]


def f32(x):
    return struct.unpack('<f', struct.pack('<f', x))[0]


def round_away(x):
    """Math.Round(x, MidpointRounding.AwayFromZero)"""
    return int(math.floor(abs(x) + 0.5)) * (1 if x >= 0 else -1)


def ceil_half(v):
    """CharacterCalcUtil.GetHalvedValueCeiling: shiny characters need half the XP."""
    return (v + 1) // 2 if v >= 0 else -((-v) // 2)


def level_cap(tier):
    return MAX_CHARACTER_LEVELS[max(0, min(tier, len(MAX_CHARACTER_LEVELS) - 1))]


class Ladder:
    """CharacterCalcUtil.IterateLevelLadder: cumulative XP needed to reach each level.

    increment(2) = base; increment(L) = round(increment(L-1) * mult) unless a reset threshold
    for level L exists, in which case increment(L) = round(base * threshold multiplier).
    """

    def __init__(self, base, mult, thresholds, cap):
        self.cap = cap
        self.totals = {1: 0}
        inc = base
        total = base
        self.totals[2] = total
        m = f32(mult)
        fbase = f32(base)
        thr = {}
        for t in thresholds:
            thr[t['level']] = t['baseLevelUpXpMultiplier']  # later entries win, like the client loop
        for level in range(3, cap + 1):
            if level in thr:
                v = f32(f32(thr[level]) * fbase)
            else:
                v = f32(m * f32(float(inc)))
            inc = round_away(v)
            total += inc
            self.totals[level] = total

    def xp_for_level(self, level, shiny=False):
        level = max(1, min(level, self.cap))
        v = self.totals[level]
        return ceil_half(v) if shiny else v

    def level_for_xp(self, xp, shiny=False):
        lvl = 1
        for level in range(2, self.cap + 1):
            if xp >= self.xp_for_level(level, shiny):
                lvl = level
            else:
                break
        return lvl

    def float_level(self, xp, shiny=False):
        lvl = self.level_for_xp(xp, shiny)
        if lvl >= self.cap:
            return float(self.cap)
        lo = self.xp_for_level(lvl, shiny)
        hi = self.xp_for_level(lvl + 1, shiny)
        return lvl + (xp - lo) / float(hi - lo) if hi > lo else float(lvl)


class Formulas:
    def __init__(self, content):
        self.c = content
        gs = content.game_settings
        self.gs = gs
        self._char_ladders = {}
        self.player_ladder = Ladder(gs['baseLevelUpPlayerXp'],
                                    f32(gs['nextLevelPlayerXpMultiplier']) * f32(gs['playerLevelingMultiplier']),
                                    gs['playerXpResetThresholds'], 100)

    # ---- characters ---------------------------------------------------------------------
    def char_ladder(self, tier):
        lad = self._char_ladders.get(tier)
        if lad is None:
            gs = self.gs
            mult = f32(f32(gs['nextLevelXpMultiplier']) * f32(gs['levelingMultiplierPerTier'][tier]))
            lad = Ladder(gs['baseLevelUpXpPerTier'][tier], mult, gs['xpResetThresholds'], level_cap(tier))
            self._char_ladders[tier] = lad
        return lad

    def base_values(self, variant, tier):
        vals = variant.get('baseScaledValuesByTier') or []
        i = tier - variant.get('initialTier', 0)
        if not vals:
            return 100, 10
        v = vals[max(0, min(i, len(vals) - 1))]
        return v['lifebar'], v['attack']

    def lifebar(self, variant, tier, level, buff=0.0):
        base, _ = self.base_values(variant, tier)
        fb = f32(float(base))
        v = f32(f32(f32(float(level - 1)) * fb) * f32(self.gs['levelScaleForLifebar']))
        v = f32(v + fb)
        return math.ceil(f32(v * f32(buff + 1.0)))

    def attack(self, variant, tier, level, buff=0.0):
        _, base = self.base_values(variant, tier)
        fb = f32(float(base))
        v = f32(f32(f32(float(level - 1)) * fb) * f32(self.gs['levelScaleForAttack']))
        v = f32(v + fb)
        return math.ceil(f32(v * f32(buff + 1.0)))

    def fighter_score(self, lifebar, attack, fs_mult_addition=0.0, super_level=0, prestige_level=0):
        gs = self.gs
        base = f32(f32(lifebar / 6.0) + attack)
        mult = f32(fs_mult_addition)
        if super_level > 0:
            mult = f32(f32(mult + gs['superAbilityUnlockFSMultiplierAddition'])
                       + f32(gs['superAbilityFSMultiplierAdditionPerLevel'] * super_level))
        if prestige_level > 0:
            if prestige_level >= 100:
                mult = f32(mult + gs['prestigeAbilityMaxFSMultiplierAddition'])
            else:
                mult = f32(f32(mult + gs['prestigeAbilityUnlockFSMultiplierAddition'])
                           + f32(gs['prestigeAbilityFSMultiplierPerLevel'] * prestige_level))
        return math.ceil(f32(f32(base + f32(base * mult)) * f32(gs['overallFighterScoreMultiplier'])))

    # ---- fights ---------------------------------------------------------------------------
    @staticmethod
    def longshot_multiplier(player_fs, enemy_fs):
        if enemy_fs <= 0 or player_fs >= enemy_fs:
            return 1.0
        v = min(f32(f32(1.0 - f32(player_fs / enemy_fs)) + 1.0), 2.0)
        return math.floor(f32(v * 10.0)) / 10.0

    def team_xp(self, player_fs, enemy_fs, won):
        mult = self.longshot_multiplier(player_fs, enemy_fs) if won else 1.0
        xp = f32(f32(mult * float(enemy_fs)) * f32(self.gs['teamXpMultiplier']))
        if not won:
            xp = min(xp * self.gs['xpGrantedOnLossFraction'], self.gs['maxXpGrantedOnLoss'])
        return int(xp)

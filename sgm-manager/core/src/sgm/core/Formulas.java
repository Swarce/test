package sgm.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Game math ported from the client (CharacterCalcUtil, VariantCharacterData). The client computes
 * in float32; Java float arithmetic reproduces that exactly.
 */
public final class Formulas {
    /** Character.kMaxCharacterLevels (Bronze, Silver, Gold, Diamond). */
    public static final int[] MAX_CHARACTER_LEVELS = {30, 40, 50, 60};

    public static int levelCap(int tier) {
        return MAX_CHARACTER_LEVELS[Math.max(0, Math.min(tier, MAX_CHARACTER_LEVELS.length - 1))];
    }

    static long roundAway(float v) {
        long r = (long) Math.floor(Math.abs((double) v) + 0.5);
        return v >= 0 ? r : -r;
    }

    static long ceilHalf(long v) {
        return v >= 0 ? (v + 1) / 2 : -((-v) / 2);
    }

    /** CharacterCalcUtil.IterateLevelLadder: cumulative XP needed to reach each level. */
    public static final class Ladder {
        public final int cap;
        private final long[] totals;

        Ladder(long base, float mult, List<Object> thresholds, int cap) {
            this.cap = cap;
            totals = new long[cap + 1];
            totals[1] = 0;
            if (cap >= 2) {
                totals[2] = base;
            }
            Map<Long, Double> thr = new HashMap<Long, Double>();
            for (Object o : thresholds) {
                Map<String, Object> t = Json.map(o);
                thr.put(Json.lng(t, "level", 0), Json.dbl(t, "baseLevelUpXpMultiplier", 1));
            }
            long inc = base;
            long total = base;
            float fbase = (float) base;
            for (int level = 3; level <= cap; level++) {
                float v;
                Double t = thr.get((long) level);
                if (t != null) {
                    v = (float) t.doubleValue() * fbase;
                } else {
                    v = mult * (float) inc;
                }
                inc = roundAway(v);
                total += inc;
                totals[level] = total;
            }
        }

        public long xpForLevel(int level, boolean shiny) {
            int l = Math.max(1, Math.min(level, cap));
            long v = totals[l];
            return shiny ? ceilHalf(v) : v;
        }

        public int levelForXp(long xp, boolean shiny) {
            int lvl = 1;
            for (int level = 2; level <= cap; level++) {
                if (xp >= xpForLevel(level, shiny)) {
                    lvl = level;
                } else {
                    break;
                }
            }
            return lvl;
        }

        public double floatLevel(long xp, boolean shiny) {
            int lvl = levelForXp(xp, shiny);
            if (lvl >= cap) {
                return cap;
            }
            long lo = xpForLevel(lvl, shiny);
            long hi = xpForLevel(lvl + 1, shiny);
            return hi > lo ? lvl + (xp - lo) / (double) (hi - lo) : lvl;
        }
    }

    private final Content c;
    public final Map<String, Object> gs;
    private final Map<Integer, Ladder> charLadders = new HashMap<Integer, Ladder>();
    public final Ladder playerLadder;

    public Formulas(Content c) {
        this.c = c;
        this.gs = c.gameSettings();
        float pm = (float) ((float) Json.dbl(gs, "nextLevelPlayerXpMultiplier", 1) * (float) Json.dbl(gs, "playerLevelingMultiplier", 1));
        playerLadder = new Ladder(Json.lng(gs, "baseLevelUpPlayerXp", 250), pm, Json.getList(gs, "playerXpResetThresholds"), 100);
    }

    public synchronized Ladder charLadder(int tier) {
        Ladder l = charLadders.get(tier);
        if (l == null) {
            List<Object> per = Json.getList(gs, "levelingMultiplierPerTier");
            List<Object> bases = Json.getList(gs, "baseLevelUpXpPerTier");
            float mult = (float) Json.dbl(gs, "nextLevelXpMultiplier", 1) * (float) ((Number) per.get(tier)).doubleValue();
            l = new Ladder(((Number) bases.get(tier)).longValue(), mult, Json.getList(gs, "xpResetThresholds"), levelCap(tier));
            charLadders.put(tier, l);
        }
        return l;
    }

    /** (lifebar, attack) base values for the tier. */
    public int[] baseValues(Map<String, Object> variant, int tier) {
        List<Object> vals = Json.getList(variant, "baseScaledValuesByTier");
        if (vals.isEmpty()) {
            return new int[]{100, 10};
        }
        int i = Math.max(0, Math.min(tier - Json.i(variant, "initialTier"), vals.size() - 1));
        Map<String, Object> v = Json.map(vals.get(i));
        return new int[]{Json.i(v, "lifebar"), Json.i(v, "attack")};
    }

    private int scaled(int base, int level, String scaleKey, float buff) {
        float fb = (float) base;
        float v = ((float) (level - 1) * fb) * (float) Json.dbl(gs, scaleKey, 0.2);
        v = v + fb;
        return (int) Math.ceil(v * (float) (buff + 1.0));
    }

    public int lifebar(Map<String, Object> variant, int tier, int level, float buff) {
        return scaled(baseValues(variant, tier)[0], level, "levelScaleForLifebar", buff);
    }

    public int attack(Map<String, Object> variant, int tier, int level, float buff) {
        return scaled(baseValues(variant, tier)[1], level, "levelScaleForAttack", buff);
    }

    public int fighterScore(int lifebar, int attack, float fsAdd, int superLevel, int prestigeLevel) {
        float base = (float) (lifebar / 6.0) + attack;
        float mult = fsAdd;
        if (superLevel > 0) {
            mult = (float) (mult + Json.dbl(gs, "superAbilityUnlockFSMultiplierAddition", 0))
                    + (float) (Json.dbl(gs, "superAbilityFSMultiplierAdditionPerLevel", 0) * superLevel);
        }
        if (prestigeLevel > 0) {
            if (prestigeLevel >= 100) {
                mult = (float) (mult + Json.dbl(gs, "prestigeAbilityMaxFSMultiplierAddition", 0));
            } else {
                mult = (float) (mult + Json.dbl(gs, "prestigeAbilityUnlockFSMultiplierAddition", 0))
                        + (float) (Json.dbl(gs, "prestigeAbilityFSMultiplierPerLevel", 0) * prestigeLevel);
            }
        }
        return (int) Math.ceil((base + base * mult) * (float) Json.dbl(gs, "overallFighterScoreMultiplier", 1));
    }

    public static double longshotMultiplier(long playerFs, long enemyFs) {
        if (enemyFs <= 0 || playerFs >= enemyFs) {
            return 1.0;
        }
        float v = Math.min((float) (1.0 - (float) ((double) playerFs / enemyFs)) + 1.0f, 2.0f);
        return Math.floor(v * 10.0f) / 10.0;
    }

    public long teamXp(long playerFs, long enemyFs, boolean won) {
        double mult = won ? longshotMultiplier(playerFs, enemyFs) : 1.0;
        double xp = (float) ((float) (mult * enemyFs) * (float) Json.dbl(gs, "teamXpMultiplier", 1));
        if (!won) {
            xp = Math.min(xp * Json.dbl(gs, "xpGrantedOnLossFraction", 0), Json.dbl(gs, "maxXpGrantedOnLoss", 0));
        }
        return (long) xp;
    }
}

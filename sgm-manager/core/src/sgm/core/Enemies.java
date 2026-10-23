package sgm.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static sgm.core.Json.arr;
import static sgm.core.Json.dbl;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.i;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;
import static sgm.core.Json.truthy;

/**
 * Opponent generation, following the client's CharacterGenerator:
 * tier = max(variant.initialTier, generator.minCharacterTier); level random in levelRange (clamped
 * to the tier's cap); skill points (level-1) * per-level points + extraSkillPoints, auto-spent
 * along GameSettings.aiSkillTree.purchaseOrder; stats computed with those buffs.
 */
public final class Enemies {
    private final Content c;
    private final Formulas f;
    private final Map<String, Object> aiTree;
    /** SkillTreeNode guid -&gt; guids of nodes that connect to it. */
    public final Map<String, List<String>> parents = new HashMap<String, List<String>>();
    final Random rnd = new Random();
    private Players players;

    public Enemies(Content c, Formulas f) {
        this.c = c;
        this.f = f;
        this.aiTree = c.get(c.gameSettings().get("_aiSkillTree"));
        for (Map<String, Object> n : c.all("SkillTreeNode")) {
            for (Object child : getList(n, "connections")) {
                Map<String, Object> ch = c.get(child);
                if (ch != null) {
                    String g = str(ch, "guid");
                    List<String> l = parents.get(g);
                    if (l == null) {
                        l = new ArrayList<String>();
                        parents.put(g, l);
                    }
                    l.add(str(n, "guid"));
                }
            }
        }
    }

    private Players players() {
        if (players == null) {
            players = new Players(c, f, obj());
        }
        return players;
    }

    private int randInt(int lo, int hi) {
        return lo + rnd.nextInt(hi - lo + 1);
    }

    private int[] range(Map<String, Object> gen, String key, int def) {
        Map<String, Object> r = getMap(gen, key);
        int lo = r == null ? def : (int) dbl(r, "x", def);
        int hi = r == null ? def : (int) dbl(r, "y", def);
        return new int[]{lo, Math.max(lo, hi)};
    }

    private Object[] pickVariant(Map<String, Object> gen, Set<String> used) {
        Map<String, Object> v = c.get(gen.get("characterData"));
        if (v != null) {
            return new Object[]{v, false};
        }
        Map<String, Object> pool = c.get(gen.get("randomCharacterList"));
        List<Map<String, Object>> all = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> fresh = new ArrayList<Map<String, Object>>();
        for (Object r : getList(pool, "characters")) {
            Map<String, Object> x = c.get(r);
            if (x != null) {
                all.add(x);
                if (!used.contains(str(x, "guid"))) {
                    fresh.add(x);
                }
            }
        }
        List<Map<String, Object>> cands = !fresh.isEmpty() ? fresh : all;
        if (cands.isEmpty()) {
            for (Map<String, Object> x : c.all("VariantCharacterData")) {
                if (truthy(x, "isInCompetitiveCollection")) {
                    cands.add(x);
                }
            }
        }
        return new Object[]{cands.get(rnd.nextInt(cands.size())), true};
    }

    private List<Object> autoSpend(int tier, int level, long extra) {
        Map<String, Object> econ = c.economy();
        long mult = Players.tierMult(getList(econ, "skillPointTierMultipliers"), tier);
        long points = lng(econ, "skillPointsOnCharacterLevelUp", 1) * mult * (level - 1) + extra;
        List<Object> bought = new ArrayList<Object>();
        if (aiTree == null) {
            return bought;
        }
        Set<String> have = new HashSet<String>();
        for (Object ref : getList(aiTree, "purchaseOrder")) {
            if (points <= 0) {
                break;
            }
            Map<String, Object> n = c.get(ref);
            if (n == null || have.contains(str(n, "guid")) || truthy(n, "isHidden")) {
                continue;
            }
            if (lng(n, "requiredLevel", 0) > level) {
                continue;
            }
            List<String> ps = parents.get(str(n, "guid"));
            if (ps != null && !ps.isEmpty()) {
                boolean any = false;
                for (String p : ps) {
                    any |= have.contains(p);
                }
                if (!any) {
                    continue;
                }
            }
            long cost = lng(n, "skillPointCost", 1) * mult;
            if (cost > points) {
                continue;
            }
            points -= cost;
            have.add(str(n, "guid"));
            bought.add(n.get("guid"));
        }
        return bought;
    }

    private List<Object> moves(Map<String, Object> variant, Map<String, Object> gen) {
        Map<String, Object> base = c.get(variant.get("baseCharacter"));
        List<Map<String, Object>> pool = new ArrayList<Map<String, Object>>();
        List<Object> refs = new ArrayList<Object>(getList(base, "specialMoves"));
        refs.addAll(getList(base, "blockbusters"));
        for (Object r : refs) {
            Map<String, Object> g = c.get(r);
            if (g != null) {
                pool.add(g);
            }
        }
        List<Object> out = new ArrayList<Object>();
        Set<String> used = new HashSet<String>();
        for (Object o : getList(gen, "gearGenerators")) {
            Map<String, Object> gg = map(o);
            Map<String, Object> g = c.get(gg.get("gearData"));
            if (g == null) {
                long mask = lng(gg, "gearTierMask", 0);
                if (mask == 0) {
                    mask = 0xF;
                }
                List<Map<String, Object>> cands = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> x : pool) {
                    if (((mask >> lng(x, "tier", 0)) & 1) != 0 && !used.contains(str(x, "guid"))) {
                        cands.add(x);
                    }
                }
                if (cands.isEmpty()) {
                    continue;
                }
                g = cands.get(rnd.nextInt(cands.size()));
            }
            used.add(str(g, "guid"));
            int[] lr = range(gg, "levelRange", 1);
            int lvl = randInt(lr[0], lr[1]);
            out.add(obj("id", Players.newId(), "moveGuid", g.get("guid"), "evolution", (long) Math.max(1, Math.min(15, lvl)),
                    "moveStatInstances", arr(), "isLocked", false, "isNew", false));
        }
        return out;
    }

    public Map<String, Object> generate(Map<String, Object> gen, Set<String> used, double fsMultAddition) {
        Object[] picked = pickVariant(gen, used);
        Map<String, Object> variant = map(picked[0]);
        used.add(str(variant, "guid"));
        int tier = Math.max(i(variant, "initialTier"), i(gen, "minCharacterTier"));
        int[] lr = range(gen, "levelRange", 1);
        int level = Math.max(1, Math.min(randInt(lr[0], lr[1]), Formulas.levelCap(tier)));
        List<Object> nodes = autoSpend(tier, level, lng(gen, "extraSkillPoints", 0));
        float[] buffs = players().skillBuffs(nodes);
        int lifebar = f.lifebar(variant, tier, level, buffs[0]);
        int attack = f.attack(variant, tier, level, buffs[1]);
        int[] sr = range(gen, "superAbilityLevelRange", 0);
        int superLevel = randInt(sr[0], sr[1]);
        int[] pr = range(gen, "prestigeAbilityLevelRange", 0);
        int prestige = randInt(pr[0], pr[1]);
        int fs = f.fighterScore(lifebar, attack, (float) (buffs[2] + fsMultAddition), superLevel, prestige);
        Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
        e.put("guid", variant.get("guid"));
        e.put("level", (long) level);
        e.put("tier", (long) tier);
        e.put("lifebar", (long) lifebar);
        e.put("attack", (long) attack);
        e.put("fighterScore", (long) fs);
        e.put("isRandom", picked[1]);
        e.put("isShiny", false);
        e.put("specialMoveInstances", moves(variant, gen));
        e.put("skillNodeGuids", nodes);
        e.put("superAbilityLevels", superLevel != 0 ? arr((long) superLevel) : arr());
        e.put("superAbilityActiveFeatureSet", lng(gen, "superAbilityFeatureSet", 0));
        e.put("prestigeAbilityLevel", (long) prestige);
        return e;
    }

    public List<Object> generateTeam(Map<String, Object> fight) {
        Set<String> used = new HashSet<String>();
        double add = dbl(fight, "enemyFighterFSMultiplierAddition", 0);
        List<Object> out = new ArrayList<Object>();
        for (Object g : getList(fight, "enemyCharacterGenerators")) {
            out.add(generate(map(g), used, add));
        }
        return out;
    }

    /** XsCharacter shape used by FightServerData.enemies (map/match preview). */
    public static Map<String, Object> preview(Map<String, Object> e) {
        return obj("variantGuid", e.get("guid"), "tier", e.get("tier"), "fighterScore", e.get("fighterScore"),
                "isShiny", e.get("isShiny"), "isSuperAbilityUnlocked", !getList(e, "superAbilityLevels").isEmpty(),
                "isPrestigeAbilityUnlocked", lng(e, "prestigeAbilityLevel", 0) > 0);
    }
}

package sgm.core.handlers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sgm.core.Ctx;
import sgm.core.Formulas;
import sgm.core.Json;
import sgm.core.Module;
import sgm.core.Players;
import sgm.core.Protocol;
import sgm.core.Rewards;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.bool;
import static sgm.core.Json.child;
import static sgm.core.Json.childList;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.i;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;
import static sgm.core.Json.truthy;

/**
 * Character and move progression: skill tree, power-up (feeding), evolution, supers, energy, move
 * upgrades and selling.
 */
public final class Progression extends Module {
    public Progression(Server app) {
        super(app);
        route("PUT", "character/{cid}/skilltree/buy/{node}", "buy_skill_node", B64 | SIGN);
        route("PUT", "character/{cid}/skilltree/buyAll", "buy_all_skill_nodes", B64 | SIGN);
        route("PUT", "character/powerUpCharacter", "power_up");
        route("PUT", "character/{cid}/evolve", "evolve");
        route("POST", "character/{cid}/convert", "convert");
        route("PUT", "character/{cid}/super/{action:unlock|upgrade|swap}", "super_ability");
        route("PUT", "characters/refill", "refill_energy");
        route("PUT", "{kind:move|assistMove}/{mid}/evolve", "evolve_move");
        route("PUT", "moves/sell", "sell_moves");
        route("PUT", "nodeModifiers/sell", "sell_node_modifiers");
        route("POST", "user/collection/{op:queryRecycleInfo|recycle}", "recycle");
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        if (name.equals("buy_skill_node")) {
            return buyNode(ctx, p.get("cid"), p.get("node"));
        } else if (name.equals("buy_all_skill_nodes")) {
            return buyAll(ctx, p.get("cid"));
        } else if (name.equals("power_up")) {
            return powerUp(ctx);
        } else if (name.equals("evolve")) {
            return evolve(ctx, p.get("cid"));
        } else if (name.equals("convert")) {
            return data(arr());
        } else if (name.equals("super_ability")) {
            return superAbility(ctx, p.get("cid"), p.get("action"));
        } else if (name.equals("refill_energy")) {
            return refill(ctx);
        } else if (name.equals("evolve_move")) {
            return evolveMove(ctx, p.get("kind"), p.get("mid"));
        } else if (name.equals("sell_moves")) {
            return sellMoves(ctx);
        } else if (name.equals("sell_node_modifiers")) {
            for (Object nid : getList(ctx.body, "ids")) {
                child(ctx.user, "nodeModifiers").remove(nid);
            }
            ctx.save();
            return data(obj());
        } else if (name.equals("recycle")) {
            return recycle(ctx, p.get("op"));
        }
        throw new IllegalStateException(name);
    }

    static Map<String, Object> character(Ctx ctx, String cid) {
        Map<String, Object> ch = cid == null ? null : map(ctx.chars().get(cid));
        if (ch == null) {
            throw err(404, "CharacterNotFound", "unknown character");
        }
        return ch;
    }

    static String baseGuid(Ctx ctx, Map<String, Object> ch) {
        String b = ctx.players().baseGuid(ch);
        return b == null ? "" : b;
    }

    /** Content Currency entry -&gt; player currency key, resolving per-element/rarity context. */
    public static String currencyName(Ctx ctx, Map<String, Object> cur, Map<String, Object> ch) {
        String t = Players.CURRENCY_TYPES.get((int) lng(cur, "currencyType", 0));
        if (t.equals("Essence") || t.equals("EssenceShard")) {
            int el = i(cur, "elementType");
            if (ch != null && !truthy(cur, "overrideContext")) {
                Map<String, Object> v = ctx.content.guid(str(ch, "variant"));
                if (v != null && v.containsKey("elementAffiliation")) {
                    el = i(v, "elementAffiliation");
                }
            }
            return Players.ELEMENTS[Math.max(0, Math.min(el, 5))] + t;
        }
        if (t.equals("GachaShard") || t.equals("Key")) {
            return Players.RARITY[Math.max(0, Math.min(i(cur, "rarityTier"), 3))] + t;
        }
        return t;
    }

    /** Accumulates a cost made of currencies and base-character skill points, then charges it atomically. */
    public static final class Bill {
        final Ctx ctx;
        final Map<String, Long> cur = new LinkedHashMap<String, Long>();
        final Map<String, Long> sp = new LinkedHashMap<String, Long>();

        public Bill(Ctx ctx) {
            this.ctx = ctx;
        }

        private static void put(Map<String, Long> m, String k, long v) {
            Long prev = m.get(k);
            m.put(k, (prev == null ? 0 : prev) + v);
        }

        public Bill add(String name, double amount) {
            if (amount > 0) {
                put(cur, name, (long) amount);
            }
            return this;
        }

        public Bill addSp(String base, double amount) {
            if (amount > 0) {
                put(sp, base, (long) amount);
            }
            return this;
        }

        public Bill addPrice(Map<String, Object> price, Map<String, Object> ch, long mult) {
            for (Object o : getList(price, "currencies")) {
                Map<String, Object> c = map(o);
                double amount = Json.dbl(c, "amount", 0) * mult;
                if ("SkillPoint".equals(Players.CURRENCY_TYPES.get((int) lng(c, "currencyType", 0))) && ch != null) {
                    addSp(baseGuid(ctx, ch), amount);
                } else {
                    add(currencyName(ctx, c, ch), amount);
                }
            }
            return this;
        }

        public void charge() {
            Map<String, Object> u = ctx.user;
            Map<String, Object> have = child(u, "currencies");
            Map<String, Object> haveSp = child(u, "skillPoints");
            for (Map.Entry<String, Long> e : cur.entrySet()) {
                if (lng(have, e.getKey(), 0) < e.getValue()) {
                    throw err(400, "NotEnoughCurrency", "not enough currency");
                }
            }
            for (Map.Entry<String, Long> e : sp.entrySet()) {
                if (lng(haveSp, e.getKey(), 0) < e.getValue()) {
                    throw err(400, "NotEnoughCurrency", "not enough currency");
                }
            }
            for (Map.Entry<String, Long> e : cur.entrySet()) {
                Json.add(have, e.getKey(), -e.getValue());
            }
            for (Map.Entry<String, Long> e : sp.entrySet()) {
                Json.add(haveSp, e.getKey(), -e.getValue());
            }
            ctx.save();
        }
    }

    // ---- skill tree -----------------------------------------------------------------------------

    public static Map<String, Object> tree(Ctx ctx, Map<String, Object> ch) {
        Map<String, Object> v = ctx.content.guid(str(ch, "variant"));
        return v == null ? null : ctx.content.get(v.get("skillTree"));
    }

    static Bill nodeBill(Ctx ctx, Map<String, Object> ch, Map<String, Object> node, boolean shortcut) {
        Map<String, Object> econ = ctx.content.economy();
        int tier = i(ch, "tier");
        Bill bill = new Bill(ctx);
        if (shortcut) {
            Map<String, Object> sc = getMap(node, "shortcutCost");
            if (sc != null) {
                bill.add(currencyName(ctx, sc, ch), Json.dbl(sc, "amount", 0) * Players.tierMult(getList(econ, "skillNodeHCCostTierMultipliers"), tier));
            }
        } else {
            bill.addSp(baseGuid(ctx, ch), lng(node, "skillPointCost", 0) * ctx.players().skillPointsPerLevel(tier));
            Map<String, Object> cost = getMap(node, "cost");
            if (cost != null) {
                long mult = lng(cost, "currencyType", -1) == 4 ? 1 : Players.tierMult(getList(econ, "skillNodeSCCostTierMultipliers"), tier);
                bill.add(currencyName(ctx, cost, ch), Json.dbl(cost, "amount", 0) * mult);
            }
        }
        return bill;
    }

    public static boolean canBuy(Ctx ctx, Map<String, Object> ch, Map<String, Object> node) {
        List<Object> owned = getList(ch, "nodes");
        String g = str(node, "guid");
        if (owned.contains(g)) {
            return false;
        }
        if (lng(node, "requiredLevel", 0) > ctx.players().charLevel(ch)) {
            return false;
        }
        for (Object r : getList(node, "multiParentNodes")) {
            Map<String, Object> m = ctx.content.get(r);
            if (m == null || !owned.contains(m.get("guid"))) {
                return false;
            }
        }
        Map<String, Object> t = tree(ctx, ch);
        Set<Object> inTree = new HashSet<Object>();
        if (t != null) {
            for (Object r : getList(t, "purchaseOrder")) {
                Map<String, Object> n = ctx.content.get(r);
                inTree.add(n == null ? null : n.get("guid"));
            }
        }
        List<String> parents = ctx.app.enemies.parents.get(g);
        if (parents != null) {
            boolean anyRelevant = false;
            boolean anyOwned = false;
            for (String par : parents) {
                if (inTree.isEmpty() || inTree.contains(par)) {
                    anyRelevant = true;
                    anyOwned |= owned.contains(par);
                }
            }
            if (anyRelevant && !anyOwned) {
                return false;
            }
        }
        for (Object r : getList(node, "mutuallyExclusiveNodes")) {
            Map<String, Object> e = ctx.content.get(r);
            if (e != null && owned.contains(e.get("guid"))) {
                return false;
            }
        }
        return true;
    }

    private Object buyNode(Ctx ctx, String cid, String nodeGuid) {
        Map<String, Object> ch = character(ctx, cid);
        Map<String, Object> n = ctx.content.guid(nodeGuid, "SkillTreeNode");
        if (n == null) {
            throw err(404, "NodeNotFound", "unknown skill node");
        }
        if (getList(ch, "nodes").contains(n.get("guid"))) {
            return empty();
        }
        if (!canBuy(ctx, ch, n)) {
            throw err(400, "NodeLocked", "node requirements not met");
        }
        nodeBill(ctx, ch, n, truthy(ctx.body, "shortcut")).charge();
        childList(ch, "nodes").add(n.get("guid"));
        Challenges.track(ctx, "upgrade_skill", 1);
        ctx.save();
        return empty();
    }

    private Object buyAll(Ctx ctx, String cid) {
        Map<String, Object> ch = character(ctx, cid);
        Map<String, Object> t = tree(ctx, ch);
        for (Object ref : getList(t, "purchaseOrder")) {
            Map<String, Object> n = ctx.content.get(ref);
            if (n == null || getList(ch, "nodes").contains(n.get("guid")) || !canBuy(ctx, ch, n)) {
                continue;
            }
            try {
                nodeBill(ctx, ch, n, false).charge();
            } catch (Protocol.ApiError e) {
                continue;
            }
            childList(ch, "nodes").add(n.get("guid"));
        }
        ctx.save();
        return empty();
    }

    // ---- power-up / evolve ----------------------------------------------------------------------------

    static void removeCharacter(Map<String, Object> u, Object cid) {
        child(u, "characters").remove(cid);
        childList(u, "team").remove(cid);
        childList(u, "defenseTeam").remove(cid);
    }

    private Object powerUp(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        Players svc = ctx.players();
        Map<String, Object> ch = character(ctx, ctx.q("cId"));
        List<String> foodIds = new ArrayList<String>();
        for (String f : ctx.qList("fIds")) {
            if (!f.equals(ch.get("id"))) {
                foodIds.add(f);
            }
        }
        if (foodIds.size() > lng(ctx.content.economy(), "feedingCardLimit", 5)) {
            throw err(400, "TooManyCards", "too many characters to feed");
        }
        long xp = 0;
        List<Object> spItems = new ArrayList<Object>();
        for (String fid : foodIds) {
            Map<String, Object> food = character(ctx, fid);
            if (bool(food, "locked") || getList(u, "team").contains(fid)) {
                throw err(400, "CharacterLocked", "cannot feed a locked or team character");
            }
            xp += svc.feedingXp(ch, food);
            long sp = svc.feedingSkillPoints(food);
            String base = baseGuid(ctx, food);
            if (sp != 0 && !base.isEmpty()) {
                Json.add(child(u, "skillPoints"), base, sp);
                spItems.add(Rewards.skillPointItem(base, sp));
            }
        }
        Map<String, Object> consume = getMap(ctx.body, "consumablesToConsumeMap");
        if (consume != null) {
            for (Map.Entry<String, Object> e : consume.entrySet()) {
                Map<String, Object> cons = ctx.content.guid(e.getKey());
                long count = e.getValue() instanceof Number ? ((Number) e.getValue()).longValue() : Long.parseLong(String.valueOf(e.getValue()));
                if (cons == null || count <= 0) {
                    continue;
                }
                Map<String, Object> have = child(u, "consumables");
                if (lng(have, e.getKey(), 0) < count) {
                    throw err(400, "NotEnoughConsumables", "not enough consumables");
                }
                Json.add(have, e.getKey(), -count);
                if (lng(cons, "consumableType", -1) == 1) { // flat XP consumable
                    xp += (long) Json.dbl(cons, "effectAmount", 0) * count;
                }
            }
        }
        for (String fid : foodIds) {
            removeCharacter(u, fid);
        }
        Formulas.Ladder lad = ctx.app.formulas.charLadder(i(ch, "tier"));
        long[] r = svc.addCharXp(u, ch, xp);
        List<Object> items = new ArrayList<Object>();
        items.add(Campaign.xpItem("CharacterXp", "characterXpReward", ch.get("id"), r[0], lng(ch, "xp", 0), lad, bool(ch, "shiny")));
        if (r[1] != 0) {
            spItems.add(Rewards.skillPointItem(svc.baseGuid(ch), r[1]));
        }
        items.addAll(spItems);
        ctx.save();
        return data(obj("rewards", arr(Rewards.bundle("CharacterFeed", str(ch, "id"), items))));
    }

    private Object evolve(Ctx ctx, String cid) {
        Map<String, Object> u = ctx.user;
        Players svc = ctx.players();
        Map<String, Object> econ = ctx.content.economy();
        Map<String, Object> ch = character(ctx, cid);
        int tier = i(ch, "tier");
        if (tier >= 3) {
            throw err(400, "MaxTier", "already diamond");
        }
        int level = svc.charLevel(ch);
        if (level < Formulas.levelCap(tier)) {
            throw err(400, "NotMaxLevel", "character must be max level");
        }
        List<String> foodIds = new ArrayList<String>();
        for (String f : ctx.qList("fIds")) {
            if (!f.equals(cid)) {
                foodIds.add(f);
            }
        }
        if (foodIds.size() < Players.tierMult(getList(econ, "cardsRequiredForEvolutionByTier"), tier)) {
            throw err(400, "NotEnoughCards", "not enough characters");
        }
        for (String fid : foodIds) {
            if (bool(character(ctx, fid), "locked")) {
                throw err(400, "CharacterLocked", "locked character");
            }
        }
        List<Object> prices = getList(econ, "priceForEvolutionByTier");
        if (tier < prices.size()) {
            new Bill(ctx).addPrice(map(prices.get(tier)), ch, 1).charge();
        }
        for (String fid : foodIds) {
            removeCharacter(u, fid);
        }
        ch.put("tier", (long) tier + 1);
        ch.put("xp", ctx.app.formulas.charLadder(tier + 1).xpForLevel(level, bool(ch, "shiny")));
        ctx.save();
        return data(obj("rewards", arr()));
    }

    // ---- super abilities ------------------------------------------------------------------------------

    private Object superAbility(Ctx ctx, String cid, String action) {
        Map<String, Object> econ = ctx.content.economy();
        Map<String, Object> ch = character(ctx, cid);
        int feature = ctx.qInt("feature", 0);
        List<Object> levels = childList(ch, "superLevels");
        while (levels.size() <= feature) {
            levels.add(0L);
        }
        String key = action.equals("unlock") ? "superAbilityUnlockCost" : action.equals("upgrade") ? "superAbilityUpgradeCost" : "superAbilitySwapCost";
        new Bill(ctx).addPrice(getMap(econ, key), ch, Players.tierMult(getList(econ, "superAbilityCostTierMultipliers"), i(ch, "tier"))).charge();
        long cur = ((Number) levels.get(feature)).longValue();
        if (action.equals("unlock")) {
            levels.set(feature, Math.max(1, cur));
            ch.put("superFeature", (long) feature);
        } else if (action.equals("upgrade")) {
            levels.set(feature, Math.min(cur + 1, 10));
        } else {
            ch.put("superFeature", (long) feature);
        }
        ctx.save();
        return empty();
    }

    // ---- energy ------------------------------------------------------------------------------------------

    private Object refill(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        List<String> ids = new ArrayList<String>();
        for (String c : ctx.qList("ids")) {
            if (ctx.chars().containsKey(c)) {
                ids.add(c);
            }
        }
        if (ids.isEmpty()) {
            return empty();
        }
        if (!ctx.qBool("adRefill")) {
            Map<String, Object> cur = child(u, "currencies");
            if (lng(cur, "EnergyRefill", 0) > 0) {
                Json.add(cur, "EnergyRefill", -1);
            } else {
                new Bill(ctx).add("HardCurrency", lng(ctx.content.economy(), "energyRefillCost", 0)).charge();
            }
        }
        for (String cid : ids) {
            Map<String, Object> ch = map(ctx.chars().get(cid));
            ch.put("energy", (long) ctx.players().maxEnergy);
            ch.put("energyTs", 0L);
        }
        ctx.save();
        return empty();
    }

    // ---- moves -------------------------------------------------------------------------------------------

    private Object evolveMove(Ctx ctx, String kind, String mid) {
        Map<String, Object> pool = child(ctx.user, kind.equals("assistMove") ? "assistMoves" : "moves");
        Map<String, Object> mv = getMap(pool, mid);
        if (mv == null) {
            throw err(404, "MoveNotFound", "unknown move");
        }
        Map<String, Object> econ = ctx.content.economy();
        Map<String, Object> g = ctx.content.guid(str(mv, "guid"));
        int levels = Math.max(1, ctx.qInt("levels", 1));
        List<Object> costs = getList(econ, "specialEvolutionCosts");
        long tm = Players.tierMult(getList(econ, "specialEvolutionCostsTierMultiplier"), g == null ? 0 : i(g, "tier"));
        Bill bill = new Bill(ctx);
        long lvl = lng(mv, "evolution", 1);
        long target = Math.min(15, lvl + levels);
        for (long l = lvl; l < target; l++) {
            if (l - 1 < costs.size() && l - 1 >= 0) {
                bill.add("SoftCurrency", ((Number) costs.get((int) (l - 1))).doubleValue() * tm);
            }
        }
        bill.charge();
        mv.put("evolution", target);
        Challenges.track(ctx, "upgrade_move", 1);
        ctx.save();
        Map<String, Object> md = ctx.players().moveData(mv);
        return data(obj("newLevel", target, "saleValue", md.get("saleValue"), "moveStatInstances", md.get("moveStatInstances")));
    }

    private Object sellMoves(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        Map<String, Object> pool = child(u, ctx.qBool("isAssist") ? "assistMoves" : "moves");
        Set<Object> equipped = new HashSet<Object>();
        for (Object o : ctx.chars().values()) {
            Map<String, Object> ch = map(o);
            equipped.addAll(getList(ch, "moves"));
            equipped.add(ch.get("assist"));
        }
        long total = 0;
        for (Object mid : getList(ctx.body, "ids")) {
            Map<String, Object> mv = getMap(pool, String.valueOf(mid));
            if (mv == null || bool(mv, "locked") || equipped.contains(mid)) {
                continue;
            }
            total += ctx.players().moveSaleValue(ctx.content.guid(str(mv, "guid")));
            pool.remove(mid);
        }
        if (total != 0) {
            ctx.players().addCurrency(u, "SoftCurrency", total);
        }
        ctx.save();
        return data(obj("rewards", total != 0 ? arr(Rewards.currencyItem("SoftCurrency", total)) : arr()));
    }

    /** Recycling characters returns skill points (like feeding) and soft currency for moves. */
    private Object recycle(Ctx ctx, String op) {
        Map<String, Object> u = ctx.user;
        Players svc = ctx.players();
        boolean apply = op.equals("recycle");
        List<Object> charItems = new ArrayList<Object>();
        List<Object> moveItems = new ArrayList<Object>();
        for (Object cid : getList(ctx.body, "characters")) {
            Map<String, Object> ch = map(ctx.chars().get(cid));
            if (ch == null || bool(ch, "locked") || getList(u, "team").contains(cid)) {
                continue;
            }
            long sp = svc.feedingSkillPoints(ch);
            String base = baseGuid(ctx, ch);
            if (sp != 0 && !base.isEmpty()) {
                charItems.add(Rewards.skillPointItem(base, sp));
                if (apply) {
                    Json.add(child(u, "skillPoints"), base, sp);
                }
            }
            if (apply) {
                removeCharacter(u, cid);
            }
        }
        long total = 0;
        Map<String, Object> moves = child(u, "moves");
        for (Object mid : getList(ctx.body, "moves")) {
            Map<String, Object> mv = getMap(moves, String.valueOf(mid));
            if (mv != null && !bool(mv, "locked")) {
                total += svc.moveSaleValue(ctx.content.guid(str(mv, "guid")));
                if (apply) {
                    moves.remove(mid);
                }
            }
        }
        if (total != 0) {
            moveItems.add(Rewards.currencyItem("SoftCurrency", total));
            if (apply) {
                svc.addCurrency(u, "SoftCurrency", total);
            }
        }
        ctx.save();
        return data(obj("characterRewards", charItems, "moveRewards", moveItems, "assistMoveRewards", arr(),
                "nodeModifierRewards", arr()));
    }
}

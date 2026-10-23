package sgm.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static sgm.core.Json.add;
import static sgm.core.Json.child;
import static sgm.core.Json.dbl;
import static sgm.core.Json.getList;
import static sgm.core.Json.i;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;
import static sgm.core.Json.truthy;

/**
 * Loot granting and reward serialization. Content Loot entries use the LootType enum; the client
 * receives rewards as RewardServerData:
 * {"key": {"type": RewardOrigin, "associatedId"}, "rewards": [{"type": RewardType, "&lt;kind&gt;Reward": {...}}]}.
 */
public final class Rewards {
    private Rewards() {
    }

    /** LootType -&gt; currency name for the simple, amount-only loot kinds. */
    static final Map<Long, String> SIMPLE = new HashMap<Long, String>();

    static {
        Object[] kv = {0L, "SoftCurrency", 1L, "HardCurrency", 12L, "BattleCurrency", 13L, "BattleTicketFree",
                14L, "BattleTicketPaid", 18L, "SocialGiftStandard", 19L, "SocialGiftGold", 20L, "EnergyRefill",
                21L, "DailyEventTicket", 22L, "CharacterGachaShard", 24L, "UniversalRerollRune", 27L, "AssistEvoCurrency",
                28L, "GuildGiftStandard", 29L, "GuildGiftPremium", 30L, "GuildOpsPoints", 31L, "RaidBossTicketFree",
                32L, "RaidBossTicketPaid"};
        for (int k = 0; k < kv.length; k += 2) {
            SIMPLE.put((Long) kv[k], (String) kv[k + 1]);
        }
    }

    public static Map<String, Object> currencyItem(String name, long amount) {
        return obj("type", "Currency", "currencyReward", obj("amounts", obj(name, amount)));
    }

    public static Map<String, Object> skillPointItem(String baseGuid, long amount) {
        return obj("type", "BaseCharacterCurrency", "baseCharacterCurrencyReward",
                obj("baseCharacterGuid", baseGuid, "amounts", obj("SkillPoint", amount)));
    }

    public static Map<String, Object> bundle(String origin, String associatedId, List<Object> items) {
        return obj("key", obj("type", origin, "associatedId", associatedId == null ? "" : associatedId), "rewards", items);
    }

    static String element(Players svc, Map<String, Object> loot) {
        int e = i(loot, "elementType");
        if (e == 6 || e >= Players.ELEMENTS.length || e < 0) {
            e = 1 + svc.rnd.nextInt(5);
        }
        return Players.ELEMENTS[e];
    }

    /** Pick one entry from a list of {"loot", "weight"} (WeightedLoot). */
    public static Map<String, Object> pickWeighted(Players svc, List<Object> weighted) {
        if (weighted == null || weighted.isEmpty()) {
            return null;
        }
        double total = 0;
        for (Object o : weighted) {
            total += Math.max(0, dbl(map(o), "weight", 0));
        }
        if (total <= 0) {
            return Json.getMap(map(weighted.get(svc.rnd.nextInt(weighted.size()))), "loot");
        }
        double r = svc.rnd.nextDouble() * total;
        for (Object o : weighted) {
            r -= Math.max(0, dbl(map(o), "weight", 0));
            if (r <= 0) {
                return Json.getMap(map(o), "loot");
            }
        }
        return Json.getMap(map(weighted.get(weighted.size() - 1)), "loot");
    }

    /** Pick one weighted loot from a LootTable. */
    public static Map<String, Object> rollTable(Players svc, Object tableRef) {
        Map<String, Object> t = svc.c.get(tableRef);
        return t == null ? null : pickWeighted(svc, getList(t, "loots"));
    }

    public static List<Object> grantLoot(Players svc, Map<String, Object> user, Map<String, Object> loot, double mult) {
        return grantLoot(svc, user, loot, mult, 0);
    }

    /** Apply one content Loot to the player; returns client reward items. */
    public static List<Object> grantLoot(Players svc, Map<String, Object> user, Map<String, Object> loot, double mult, int depth) {
        Content c = svc.c;
        List<Object> out = new ArrayList<Object>();
        if (loot == null) {
            return out;
        }
        long lt = lng(loot, "lootType", 8);
        long amount = (long) Math.rint(dbl(loot, "amount", 0) * mult);
        int count = Math.max(1, i(loot, "amount"));
        String simple = SIMPLE.get(lt);
        if (simple != null) {
            if (amount > 0) {
                svc.addCurrency(user, simple, amount);
                out.add(currencyItem(simple, amount));
            }
        } else if (lt == 6) { // GachaShards per rarity
            String name = Players.RARITY[Math.max(1, Math.min(i(loot, "rarityTier", 1), 3))] + "GachaShard";
            svc.addCurrency(user, name, amount);
            out.add(currencyItem(name, amount));
        } else if (lt == 9) { // Keys per rarity
            String name = Players.RARITY[Math.max(0, Math.min(i(loot, "rarityTier"), 3))] + "Key";
            svc.addCurrency(user, name, amount);
            out.add(currencyItem(name, amount));
        } else if (lt == 10 || lt == 11) { // (essence) shards / essences per element
            String name = element(svc, loot) + (lt == 10 ? "EssenceShard" : "Essence");
            svc.addCurrency(user, name, amount);
            out.add(currencyItem(name, amount));
        } else if (lt == 23 || lt == 7) { // RerollRune / SkillPoints per base character
            Map<String, Object> base = c.get(loot.get("baseCharacter"));
            if (base != null) {
                String g = str(base, "guid");
                add(child(user, lt == 23 ? "rerollRunes" : "skillPoints"), g, amount);
                out.add(obj("type", "BaseCharacterCurrency", "baseCharacterCurrencyReward",
                        obj("baseCharacterGuid", g, "amounts", obj(lt == 23 ? "RerollRune" : "SkillPoint", amount))));
            }
        } else if (lt == 3) { // Character
            Map<String, Object> v = c.get(loot.get("character"));
            if (v != null) {
                int rt = i(loot, "rarityTier");
                for (int k = 0; k < count; k++) {
                    boolean shiny = truthy(loot, "forceShiny") || svc.rnd.nextDouble() < svc.shinyChance(v);
                    Map<String, Object> ch = svc.grantCharacter(user, str(v, "guid"), rt != 0 ? rt : null, 0, shiny);
                    Map<String, Object> item = obj("type", "Character", "characterReward",
                            obj("variantGuid", v.get("guid"), "characterInstanceId", ch.get("id"), "isShiny", ch.get("shiny")));
                    item.put("isFirstTimeAppearance", false);
                    out.add(item);
                }
            }
        } else if (lt == 2 || lt == 26) { // SpecialMove / AssistMove
            Map<String, Object> g = c.get(loot.get("gear"));
            if (g != null) {
                for (int k = 0; k < count; k++) {
                    // the Python server keeps both kinds in "moves"; assist moves go to their own pool here
                    Map<String, Object> mv = svc.grantMove(user, lt == 2 ? "moves" : "assistMoves", str(g, "guid"), true);
                    out.add(obj("type", lt == 2 ? "Move" : "AssistMove", lt == 2 ? "moveReward" : "assistMoveReward",
                            obj("moveGuid", g.get("guid"), "moveInstanceId", mv.get("id"), "statGuids", Json.arr())));
                }
            }
        } else if (lt == 4) { // Gacha (relic)
            Map<String, Object> g = truthy(loot, "reel") ? c.get(loot.get("reel")) : null;
            if (g != null && "GachaData".equals(g.get("_class"))) {
                add(child(user, "gachas"), str(g, "guid"), amount);
                out.add(obj("type", "Gacha", "gachaReward", obj("guid", g.get("guid"), "count", amount)));
            }
        } else if (lt == 16) { // Consumable
            Map<String, Object> cons = c.get(loot.get("consumable"));
            if (cons != null) {
                add(child(user, "consumables"), str(cons, "guid"), amount);
                out.add(obj("type", "Consumable", "consumableReward", obj("guid", cons.get("guid"), "count", amount)));
            }
        } else if (lt == 5) { // nested LootTable
            if (depth <= 8) {
                for (int k = 0; k < count; k++) {
                    Map<String, Object> picked = rollTable(svc, loot.get("nestedLootTable"));
                    if (picked != null) {
                        out.addAll(grantLoot(svc, user, picked, mult, depth + 1));
                    }
                }
            }
        } else if (lt == 15) { // NodeModifier
            Map<String, Object> nm = c.get(loot.get("nodeModifier"));
            if (nm != null) {
                String nid = Players.newId();
                String element = element(svc, loot);
                child(user, "nodeModifiers").put(nid, obj("id", nid, "guid", nm.get("guid"), "level", 1L, "element", element, "new", true));
                out.add(obj("type", "NodeModifier", "nodeModifierReward",
                        obj("guid", nm.get("guid"), "element", element, "nodeModifierInstanceId", nid)));
            }
        }
        // 8 None, 17 GachaVoucher, 25 Cosmetic: not tracked
        return out;
    }
}

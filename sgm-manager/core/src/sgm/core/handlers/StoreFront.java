package sgm.core.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import sgm.core.Ctx;
import sgm.core.Json;
import sgm.core.Module;
import sgm.core.Rewards;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.child;
import static sgm.core.Json.dbl;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;

/** Relic (gacha) store: listing, buying with in-game currency, opening owned relics. */
public final class StoreFront extends Module {
    /** evergreen relics shown when config "store_gachas" is not set */
    static final Pattern DEFAULT_STORE = Pattern.compile("^Gacha_\\d+_(Bronze|Premiere|Moves_\\w+|Gold|Silver|Diamond|Sharded|Air|Fire|Water|Light|Dark|"
            + "Daily|Char_[A-Za-z]+)$");

    public StoreFront(Server app) {
        super(app);
        route("GET", "store/gachas/{region}", "available_gachas");
        route("GET", "user/gachas", "owned_gachas");
        route("POST", "store/gacha/{mode:buy|voucherBuy}/{guid}", "buy_gacha");
        route("PUT", "user/gacha/open/{guid}", "open_owned");
        route("POST", "store/gacha/convertToShards/{guid}", "convert_to_shards");
    }

    public static List<Map<String, Object>> storeGachas(Server app) {
        List<Object> cfg = getList(app.config, "store_gachas");
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> g : app.content.all("GachaData")) {
            String n = str(g, "_name");
            if (!cfg.isEmpty()) {
                if (cfg.contains(g.get("guid"))) {
                    out.add(g);
                }
            } else if (DEFAULT_STORE.matcher(n).matches() && !n.contains("NA") && !n.contains("DEBUG")) {
                out.add(g);
            }
        }
        return out;
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        if (name.equals("available_gachas")) {
            List<Object> out = new ArrayList<Object>();
            for (Map<String, Object> g : storeGachas(app)) {
                out.add(obj("gachaGuid", g.get("guid"), "isEarnOverTime", false,
                        "schedulingStatus", obj("currentlyActive", true, "startDate", -1L, "endDate", -1L)));
            }
            return data(obj("gachas", out));
        } else if (name.equals("owned_gachas")) {
            List<Object> out = new ArrayList<Object>();
            for (Map.Entry<String, Object> e : child(ctx.user, "gachas").entrySet()) {
                long n = ((Number) e.getValue()).longValue();
                if (n > 0) {
                    out.add(obj("guid", e.getKey(), "count", n));
                }
            }
            return data(out);
        } else if (name.equals("buy_gacha")) {
            Map<String, Object> g = ctx.content.guid(p.get("guid"), "GachaData");
            if (g == null) {
                throw err(404, "GachaNotFound", "unknown relic");
            }
            int count = Math.max(1, ctx.qInt("count", 1));
            Progression.Bill bill = new Progression.Bill(ctx);
            for (Object o : getList(getMap(g, "price"), "currencies")) {
                Map<String, Object> cur = map(o);
                bill.add(Progression.currencyName(ctx, cur, null), dbl(cur, "amount", 0) * count);
            }
            bill.charge();
            if (ctx.qBool("openAll", true)) {
                return open(ctx, g, count);
            }
            Json.add(child(ctx.user, "gachas"), p.get("guid"), count);
            ctx.save();
            return data(obj());
        } else if (name.equals("open_owned")) {
            String guid = p.get("guid");
            Map<String, Object> g = ctx.content.guid(guid);
            long have = lng(child(ctx.user, "gachas"), guid, 0);
            int count = Math.max(1, ctx.qInt("count", 1));
            if (g == null || have < count) {
                throw err(400, "NotEnoughRelics", "relic not owned");
            }
            child(ctx.user, "gachas").put(guid, have - count);
            return open(ctx, g, count);
        } else if (name.equals("convert_to_shards")) {
            return data(obj());
        }
        throw new IllegalStateException(name);
    }

    /** One relic: weighted LootTableSet, then one roll per table in the set. */
    public static List<Object> openRelic(Ctx ctx, Map<String, Object> gacha) {
        List<Object> sets = getList(gacha, "lootTableSets");
        double total = 0;
        for (Object s : sets) {
            total += dbl(map(s), "weight", 0);
        }
        Map<String, Object> pick = null;
        double r = total > 0 ? ctx.players().rnd.nextDouble() * total : 0;
        for (Object s : sets) {
            r -= dbl(map(s), "weight", 0);
            if (r <= 0) {
                pick = map(s);
                break;
            }
        }
        if (pick == null && !sets.isEmpty()) {
            pick = map(sets.get(0));
        }
        List<Object> items = new ArrayList<Object>();
        Map<String, Object> lts = pick == null ? null : ctx.content.get(pick.get("lootTableSet"));
        for (Object table : getList(lts, "lootTables")) {
            Map<String, Object> loot = Rewards.rollTable(ctx.players(), table);
            if (loot != null) {
                items.addAll(Rewards.grantLoot(ctx.players(), ctx.user, loot, 1));
            }
        }
        return items;
    }

    private Object open(Ctx ctx, Map<String, Object> gacha, int count) {
        Challenges.track(ctx, "open_relic", count);
        List<Object> rewards = new ArrayList<Object>();
        for (int k = 0; k < count; k++) {
            rewards.addAll(openRelic(ctx, gacha));
        }
        ctx.save();
        return data(obj("rewards", rewards));
    }
}

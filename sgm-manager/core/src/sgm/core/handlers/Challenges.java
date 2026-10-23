package sgm.core.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sgm.core.Content;
import sgm.core.Ctx;
import sgm.core.Module;
import sgm.core.Rewards;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.child;
import static sgm.core.Json.childList;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;

/**
 * Daily challenges ("missions" list on the main menu). ChallengeManager.challengesDatas holds the
 * daily pool; each claimed challenge awards pointReward points and every 100/len(thresholds)
 * points unlocks one of EconomySettings.challengesThresholdRewards. Only challenges that can
 * progress without multiplayer are offered.
 */
public final class Challenges extends Module {
    static final long DAY_MS = 86400000L;

    /** challenge class -&gt; progress key reported by track(). */
    static final Map<String, String> TRACKERS = new HashMap<String, String>();

    static {
        TRACKERS.put("LogInChallengeData", "login");
        TRACKERS.put("OpenGachaChallengeData", "open_relic");
        TRACKERS.put("UpgradeMoveChallengeData", "upgrade_move");
        TRACKERS.put("UpgradeCharacterChallengeData", "upgrade_skill");
    }

    public Challenges(Server app) {
        super(app);
        route("GET", "challenges", "challenges");
        route("PUT", "challenge/{guid}/claim", "claim_challenge");
        route("PUT", "challenges/reward/{idx:\\d+}/claim/{double:\\w+}", "claim_threshold");
        route("PUT", "challenge/update/login", "challenge_login");
    }

    static String kind(Map<String, Object> ch) {
        if ("WinFightsInModeChallengeData".equals(ch.get("_class"))) {
            long mode = lng(ch, "mode", -1); // WinFightsInModeChallengeData.mode (GameType)
            return mode == 0 ? "win_story" : mode == 7 ? "win_lta" : null;
        }
        return TRACKERS.get(str(ch, "_class"));
    }

    static List<Map<String, Object>> dailyPool(Content c) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> cm : c.all("ChallengeManager")) {
            for (Object ref : getList(cm, "challengesDatas")) {
                Map<String, Object> ch = c.get(ref);
                if (ch != null && lng(ch, "active", 1) != 0 && kind(ch) != null) {
                    out.add(ch);
                }
            }
            break;
        }
        return out;
    }

    static Map<String, Object> state(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        long day = (ctx.nowMs / DAY_MS);
        Map<String, Object> st = getMap(u, "daily");
        if (st == null || st.isEmpty() || lng(st, "day", -1) != day) {
            st = obj("day", day, "progress", new LinkedHashMap<String, Object>(), "claimed", arr(), "thresholds", arr());
            u.put("daily", st);
        }
        return st;
    }

    /** Advance every active daily challenge of this kind. */
    public static void track(Ctx ctx, String kind, long amount) {
        if (ctx.user == null) {
            return;
        }
        Map<String, Object> st = state(ctx);
        Map<String, Object> progress = child(st, "progress");
        List<Object> claimed = childList(st, "claimed");
        for (Map<String, Object> ch : dailyPool(ctx.content)) {
            String g = str(ch, "guid");
            if (kind.equals(kind(ch)) && !claimed.contains(g)) {
                progress.put(g, Math.min(lng(ch, "target", 1), lng(progress, g, 0) + amount));
            }
        }
        ctx.save();
    }

    static long points(Ctx ctx, Map<String, Object> st) {
        Map<String, Map<String, Object>> pool = new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> c : dailyPool(ctx.content)) {
            pool.put(str(c, "guid"), c);
        }
        long sum = 0;
        for (Object g : getList(st, "claimed")) {
            Map<String, Object> c = pool.get(g);
            if (c != null) {
                sum += lng(c, "pointReward", 0);
            }
        }
        return sum;
    }

    static List<Object> thresholds(Ctx ctx) {
        return getList(ctx.content.economy(), "challengesThresholdRewards");
    }

    /** Counters UserServerData exposes for badges. */
    public static Map<String, Object> summary(Ctx ctx) {
        Map<String, Object> st = state(ctx);
        Map<String, Object> progress = child(st, "progress");
        List<Object> claimed = childList(st, "claimed");
        long claimable = 0;
        for (Map<String, Object> c : dailyPool(ctx.content)) {
            String g = str(c, "guid");
            if (!claimed.contains(g) && lng(progress, g, 0) >= lng(c, "target", 1)) {
                claimable++;
            }
        }
        List<Object> th = thresholds(ctx);
        long interval = th.isEmpty() ? 0 : 100 / th.size();
        long unlocked = interval != 0 ? Math.min(th.size(), points(ctx, st) / interval) : 0;
        return obj("claimableChallengeCount", claimable,
                "claimableChallengeRewardThresholdCount", Math.max(0, unlocked - getList(st, "thresholds").size()),
                "challengeExpirationDate", (lng(st, "day", 0) + 1) * DAY_MS);
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        Map<String, Object> st = state(ctx);
        Map<String, Object> progress = child(st, "progress");
        List<Object> claimed = childList(st, "claimed");
        if (name.equals("challenges")) {
            List<Object> out = new ArrayList<Object>();
            for (Map<String, Object> c : dailyPool(ctx.content)) {
                String g = str(c, "guid");
                long n = lng(progress, g, 0);
                String status = claimed.contains(g) ? "Claimed" : (n >= lng(c, "target", 1) ? "Completed" : "InProgress");
                out.add(obj("challengeGuid", g, "count", n, "status", status, "constraints", arr()));
            }
            List<Object> claimedRewards = new ArrayList<Object>();
            List<Object> done = getList(st, "thresholds");
            for (int k = 0; k < thresholds(ctx).size(); k++) {
                claimedRewards.add(done.contains((long) k));
            }
            return data(obj("challenges", out, "claimedRewards", claimedRewards,
                    "expirationDate", (lng(st, "day", 0) + 1) * DAY_MS, "newChallengesAvailable", false));
        } else if (name.equals("claim_challenge")) {
            String guid = p.get("guid");
            Map<String, Object> c = null;
            for (Map<String, Object> x : dailyPool(ctx.content)) {
                if (guid.equals(x.get("guid"))) {
                    c = x;
                }
            }
            if (c == null || claimed.contains(guid)) {
                throw err(400, "ChallengeNotClaimable", "challenge not claimable");
            }
            if (lng(progress, guid, 0) < lng(c, "target", 1)) {
                throw err(400, "ChallengeNotComplete", "challenge not complete");
            }
            claimed.add(guid);
            ctx.save();
            return empty();
        } else if (name.equals("claim_threshold")) {
            List<Object> th = thresholds(ctx);
            int i = Integer.parseInt(p.get("idx"));
            List<Object> done = childList(st, "thresholds");
            long interval = th.isEmpty() ? 0 : 100 / th.size();
            if (i >= th.size() || done.contains((long) i) || interval == 0 || points(ctx, st) < (i + 1) * interval) {
                throw err(400, "RewardNotClaimable", "threshold not reached");
            }
            done.add((long) i);
            List<Object> items = new ArrayList<Object>();
            for (Object loot : getList(map(th.get(i)), "loots")) {
                items.addAll(Rewards.grantLoot(ctx.players(), ctx.user, map(loot), 1));
            }
            ctx.save();
            return data(arr(Rewards.bundle("Undefined", String.valueOf(i), items)));
        } else if (name.equals("challenge_login")) {
            track(ctx, "login", 1);
            return empty();
        }
        throw new IllegalStateException(name);
    }
}

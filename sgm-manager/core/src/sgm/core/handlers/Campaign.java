package sgm.core.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sgm.core.Content;
import sgm.core.Ctx;
import sgm.core.Enemies;
import sgm.core.Formulas;
import sgm.core.Json;
import sgm.core.Module;
import sgm.core.Players;
import sgm.core.Rewards;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.bool;
import static sgm.core.Json.child;
import static sgm.core.Json.dbl;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.i;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.orD;
import static sgm.core.Json.str;
import static sgm.core.Json.truthy;

/** Story/campaign acts and the fight lifecycle (prep -&gt; start -&gt; end). */
public final class Campaign extends Module {
    static final long CAMPAIGN = 0;

    public Campaign(Server app) {
        super(app);
        route("GET", "acts/campaign", "available_campaign_acts");
        route("PUT", "acts/progress", "acts_progress");
        route("POST", "fight", "prep_fight", B64 | SIGN);
        route("PUT", "fight/{fid}/start", "start_fight", B64 | SIGN);
        route("PUT", "fight/{fid}/end", "end_fight", B64 | SIGN);
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        if (name.equals("available_campaign_acts")) {
            List<Object> out = new ArrayList<Object>();
            for (Map<String, Object> a : campaignActs(ctx.content)) {
                out.add(a.get("guid"));
            }
            return data(out);
        } else if (name.equals("acts_progress")) {
            List<Object> out = new ArrayList<Object>();
            for (Object d : getList(ctx.body, "descriptors")) {
                Map<String, Object> dm = map(d);
                String guid = dm != null ? str(dm, "guid") : String.valueOf(d);
                String iid = dm != null ? str(dm, "eventInstanceId", null) : null;
                Map<String, Object> act = ctx.content.guid(guid, "Act");
                if (act != null) {
                    out.add(actProgress(ctx, act, iid));
                }
            }
            return data(out);
        } else if (name.equals("prep_fight")) {
            return prepFight(ctx);
        } else if (name.equals("start_fight")) {
            return startFight(ctx, p.get("fid"));
        } else if (name.equals("end_fight")) {
            return endFight(ctx, p.get("fid"));
        }
        throw new IllegalStateException(name);
    }

    public static List<Map<String, Object>> campaignActs(Content c) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> a : c.all("Act")) {
            if (lng(a, "gameType", -1) == CAMPAIGN && !str(a, "_name").contains("FTUE")) {
                out.add(a);
            }
        }
        return out;
    }

    /** Match progress lives in user.campaign for the story, or per event instance for events. */
    static Map<String, Object> progressScope(Ctx ctx, String eventInstanceId) {
        if (eventInstanceId != null && !eventInstanceId.isEmpty()) {
            Map<String, Object> ev = getMap(child(ctx.user, "events"), eventInstanceId);
            if (ev != null) {
                return child(ev, "progress");
            }
        }
        return child(ctx.user, "campaign");
    }

    /** Per-player state of one match, generating (and caching) its opponents. */
    static Map<String, Object> matchState(Ctx ctx, Map<String, Object> match, Map<String, Object> scope) {
        if (scope == null) {
            scope = child(ctx.user, "campaign");
        }
        String g = str(match, "guid");
        Map<String, Object> st = getMap(scope, g);
        if (st == null) {
            st = obj("completed", false, "challenges", 0L, "wins", 0L);
            scope.put(g, st);
        }
        List<Map<String, Object>> fights = ctx.content.fightsOf(match);
        if (getList(st, "enemies").size() != fights.size()) {
            List<Object> teams = new ArrayList<Object>();
            for (Map<String, Object> f : fights) {
                teams.add(ctx.app.enemies.generateTeam(f));
            }
            st.put("enemies", teams);
            ctx.save();
        }
        return st;
    }

    static Map<String, Object> actProgress(Ctx ctx, Map<String, Object> act, String eventInstanceId) {
        Map<String, Object> scope = progressScope(ctx, eventInstanceId);
        List<Object> chapters = new ArrayList<Object>();
        for (Map<String, Object> ch : ctx.content.chaptersOf(act)) {
            List<Object> matches = new ArrayList<Object>();
            for (Map<String, Object> m : ctx.content.matchesOf(ch)) {
                Map<String, Object> st = matchState(ctx, m, scope);
                List<Object> fights = new ArrayList<Object>();
                for (Object team : getList(st, "enemies")) {
                    List<Object> enemies = new ArrayList<Object>();
                    for (Object e : Json.list(team)) {
                        enemies.add(Enemies.preview(map(e)));
                    }
                    fights.add(obj("enemies", enemies));
                }
                matches.add(obj("guid", m.get("guid"), "isFinal", truthy(m, "isCompletionNode"),
                        "isCompleted", bool(st, "completed"), "matchChallengesProgress", lng(st, "challenges", 0) & 0xFF,
                        "fights", fights));
            }
            chapters.add(obj("guid", ch.get("guid"), "matches", matches));
        }
        return obj("guid", act.get("guid"), "chapters", chapters);
    }

    // ---- fights ---------------------------------------------------------------------------------

    static final String[] ENEMY_KEYS = {"guid", "level", "tier", "lifebar", "attack", "fighterScore", "isRandom", "isShiny",
            "specialMoveInstances", "skillNodeGuids", "superAbilityLevels", "superAbilityActiveFeatureSet", "prestigeAbilityLevel"};

    static Map<String, Object> fightInstance(String fid, Map<String, Object> fight, List<Object> enemies) {
        List<Object> chars = new ArrayList<Object>();
        for (Object o : enemies) {
            Map<String, Object> e = map(o);
            Map<String, Object> pe = new LinkedHashMap<String, Object>();
            for (String k : ENEMY_KEYS) {
                pe.put(k, e.get(k));
            }
            chars.add(pe);
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("id", fid);
        out.put("difficulty", fight.containsKey("difficulty") ? fight.get("difficulty") : -1L);
        out.put("opponentId", "");
        out.put("enemyFighterFSMultiplierAddition", orD(fight, "enemyFighterFSMultiplierAddition", 0.0));
        out.put("enemyName", "");
        out.put("enemyIsDeveloper", false);
        out.put("defenseBonusCount", 0L);
        out.put("characters", chars);
        return out;
    }

    private static void rememberFight(Ctx ctx, Map<String, Object> rec) {
        final Map<String, Object> fights = child(ctx.user, "fights");
        if (fights.size() > 30) { // drop stale unfinished fights
            List<String> keys = new ArrayList<String>(fights.keySet());
            Collections.sort(keys, new Comparator<String>() {
                @Override
                public int compare(String a, String b) {
                    long x = lng(map(fights.get(a)), "created", 0);
                    long y = lng(map(fights.get(b)), "created", 0);
                    return x < y ? -1 : (x == y ? 0 : 1);
                }
            });
            for (String k : keys.subList(0, fights.size() - 30)) {
                fights.remove(k);
            }
        }
        fights.put(str(rec, "id"), rec);
    }

    private Object prepFight(Ctx ctx) {
        Map<String, Object> b = ctx.body;
        String kind = str(b, "type");
        if (kind.isEmpty()) {
            kind = "Campaign";
        }
        Map<String, Object> match = ctx.content.guid(str(b, "matchGuid"), "SGMatch");
        if (match == null) {
            throw err(404, "MatchNotFound", "unknown match " + str(b, "matchGuid"));
        }
        String iid = str(b, "eventInstanceId");
        if (!iid.isEmpty()) {
            Map<String, Object> ev = getMap(child(ctx.user, "events"), iid);
            if (ev == null || !bool(ev, "entered")) {
                throw err(400, "EventNotEntered", "enter the event first");
            }
        }
        Map<String, Object> st = matchState(ctx, match, progressScope(ctx, iid));
        List<Object> out = new ArrayList<Object>();
        List<Map<String, Object>> fights = ctx.content.fightsOf(match);
        for (int k = 0; k < fights.size(); k++) {
            String fid = Players.newId();
            List<Object> enemies = Json.list(getList(st, "enemies").get(k));
            Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("id", fid);
            rec.put("kind", kind);
            rec.put("match", match.get("guid"));
            rec.put("act", b.get("actGuid"));
            rec.put("chapter", b.get("chapterGuid"));
            rec.put("event", b.get("eventInstanceId"));
            rec.put("index", (long) k);
            rec.put("enemies", enemies);
            rec.put("created", Players.nowMs());
            rec.put("team", arr());
            rememberFight(ctx, rec);
            out.add(fightInstance(fid, fights.get(k), enemies));
        }
        ctx.save();
        return data(obj("fights", out));
    }

    private Object startFight(Ctx ctx, String fid) {
        Map<String, Object> rec = getMap(child(ctx.user, "fights"), fid);
        if (rec == null) {
            throw err(404, "FightNotFound", "unknown fight");
        }
        List<Object> team = new ArrayList<Object>();
        for (Object cid : getList(ctx.body, "teamIds")) {
            if (ctx.chars().containsKey(cid)) {
                team.add(cid);
            }
        }
        Map<String, Object> match = ctx.content.guid(str(rec, "match"));
        Map<String, Object> fight = ctx.content.fightsOf(match).get(i(rec, "index"));
        long cost = lng(fight, "energyRequirement", 0);
        if (cost != 0) {
            for (Object cid : team) {
                Map<String, Object> ch = map(ctx.chars().get(cid));
                ctx.players().refreshEnergy(ch);
                if (lng(ch, "energy", 0) < cost) {
                    throw err(400, "NotEnoughEnergy", "not enough energy");
                }
            }
            for (Object cid : team) {
                Map<String, Object> ch = map(ctx.chars().get(cid));
                Json.add(ch, "energy", -cost);
                ctx.players().refreshEnergy(ch);
            }
        }
        rec.put("team", team);
        rec.put("started", Players.nowMs());
        ctx.save();
        return data(obj("uploadReplay", false));
    }

    static long completedChallenges(List<Object> stats) {
        long bits = 0;
        for (Object o : stats) {
            Map<String, Object> s = map(o);
            if (s == null || !"MatchChallenge".equals(s.get("type"))) {
                continue;
            }
            List<Object> params = getList(s, "params");
            long idx;
            try {
                idx = params.isEmpty() ? 0 : Long.parseLong(String.valueOf(params.get(0)).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (dbl(s, "count", 0) > 0 && idx >= 0 && idx < 8) {
                bits |= 1L << idx;
            }
        }
        return bits;
    }

    static Map<String, Object> xpItem(String kind, String key, Object instanceId, long beforeXp, long afterXp,
                                      Formulas.Ladder ladder, boolean shiny) {
        return obj("type", kind, key, obj("instanceId", instanceId, "total", afterXp, "gained", afterXp - beforeXp,
                "level", (long) ladder.levelForXp(afterXp, shiny),
                "from", ladder.floatLevel(beforeXp, shiny), "to", ladder.floatLevel(afterXp, shiny)));
    }

    /** Adds player XP and level-up rewards; returns the PlayerXp reward item. */
    static Map<String, Object> grantPlayerXp(Ctx ctx, double amount, List<Object> bundles) {
        Players svc = ctx.players();
        Map<String, Object> u = ctx.user;
        long before = lng(u, "xp", 0);
        int oldLevel = svc.level(u);
        Json.add(u, "xp", Math.max(0, (long) amount));
        int newLevel = svc.level(u);
        Map<String, Object> item = xpItem("PlayerXp", "playerXpReward", u.get("id"), before, lng(u, "xp", 0),
                ctx.app.formulas.playerLadder, false);
        if (newLevel > oldLevel) {
            List<Object> items = new ArrayList<Object>();
            for (Object o : getList(ctx.content.economy(), "playerLevelUpRewards")) {
                Map<String, Object> entry = map(o);
                long lvl = lng(entry, "level", 0);
                if (oldLevel < lvl && lvl <= newLevel) {
                    for (Object loot : getList(entry, "loots")) {
                        items.addAll(Rewards.grantLoot(svc, u, map(loot), 1));
                    }
                }
            }
            if (!items.isEmpty()) {
                bundles.add(Rewards.bundle("LevelUp", String.valueOf(newLevel), items));
            }
        }
        return item;
    }

    private Object endFight(Ctx ctx, String fid) {
        Map<String, Object> u = ctx.user;
        Map<String, Object> rec = map(child(u, "fights").remove(fid));
        if (rec == null) {
            throw err(404, "FightNotFound", "unknown fight");
        }
        ctx.save();
        boolean won = "won".equals(ctx.q("status", "lost"));
        Content c = ctx.content;
        Players svc = ctx.players();
        Formulas F = ctx.app.formulas;
        Map<String, Object> gs = c.gameSettings();
        Map<String, Object> match = c.guid(str(rec, "match"));
        List<Map<String, Object>> matchFights = c.fightsOf(match);
        int index = i(rec, "index");
        Map<String, Object> fight = matchFights.get(index);
        String event = str(rec, "event");
        Map<String, Object> scope = progressScope(ctx, event);
        Map<String, Object> st = matchState(ctx, match, scope);
        boolean replay = bool(st, "completed");

        List<Map<String, Object>> team = new ArrayList<Map<String, Object>>();
        for (Object cid : getList(rec, "team")) {
            Map<String, Object> ch = map(ctx.chars().get(cid));
            if (ch != null) {
                team.add(ch);
            }
        }
        long playerFs = 0;
        for (Map<String, Object> ch : team) {
            playerFs += svc.charStats(ch)[3];
        }
        long enemyFs = 0;
        for (Object e : getList(rec, "enemies")) {
            enemyFs += lng(map(e), "fighterScore", 0);
        }

        List<Object> bundles = new ArrayList<Object>();
        List<Object> fightItems = new ArrayList<Object>();
        // character XP
        double xp = F.teamXp(playerFs, enemyFs, won);
        if (dbl(fight, "xpOverride", -1) >= 0) {
            xp = won ? dbl(fight, "xpOverride", 0) : 0;
        }
        xp = (long) (xp * orD(fight, "xpMultiplier", 1.0) * app.cfgD("xp_rate", 1.0));
        if (replay) {
            xp = (long) (xp * dbl(gs, "storyModeReplayXpMultiplier", 1.0));
        }
        if (truthy(fight, "skipXP")) {
            xp = 0;
        }
        List<Object> levelItems = new ArrayList<Object>();
        for (Map<String, Object> ch : team) {
            Formulas.Ladder lad = F.charLadder(i(ch, "tier"));
            long[] r = svc.addCharXp(u, ch, (long) xp);
            fightItems.add(xpItem("CharacterXp", "characterXpReward", ch.get("id"), r[0], lng(ch, "xp", 0), lad, bool(ch, "shiny")));
            String base = svc.baseGuid(ch);
            if (r[1] != 0) {
                levelItems.add(Rewards.skillPointItem(base, r[1]));
            }
        }
        if (!levelItems.isEmpty()) {
            bundles.add(Rewards.bundle("CharacterLevelUp", fid, levelItems));
        }

        long challengeBits = 0;
        if (won) {
            Challenges.track(ctx, event.isEmpty() ? "win_story" : "win_lta", 1);
            double mult = orD(fight, "rewardMultiplier", 1.0);
            if (replay) {
                mult *= dbl(gs, "replayRewardMultiplier", 1.0);
            }
            long sc = (long) (dbl(c.economy(), "fightBaseSoftCurrencyReward", 0) * mult * app.cfgD("currency_rate", 1.0));
            if (sc > 0) {
                svc.addCurrency(u, "SoftCurrency", sc);
                fightItems.add(Rewards.currencyItem("SoftCurrency", sc));
            }
            fightItems.add(grantPlayerXp(ctx, enemyFs * app.cfgD("player_xp_per_enemy_fs", 0.25), bundles));
            Map<String, Object> drop = Rewards.pickWeighted(svc, getList(match, "randomDropLoots"));
            if (drop != null) {
                fightItems.addAll(Rewards.grantLoot(svc, u, drop, 1));
            }

            // match challenges completed this fight (first completion grants their rewards)
            challengeBits = completedChallenges(getList(ctx.body, "stats"));
            long newBits = challengeBits & ~lng(st, "challenges", 0);
            List<Object> chItems = new ArrayList<Object>();
            List<Object> mcs = getList(fight, "matchChallenges");
            for (int k = 0; k < mcs.size(); k++) {
                if ((newBits & (1L << k)) != 0) {
                    for (Object loot : getList(map(mcs.get(k)), "rewards")) {
                        chItems.addAll(Rewards.grantLoot(svc, u, map(loot), 1));
                    }
                }
            }
            st.put("challenges", lng(st, "challenges", 0) | challengeBits);
            if (!chItems.isEmpty()) {
                bundles.add(Rewards.bundle("MatchChallenge", str(match, "guid"), chItems));
            }

            boolean lastFight = index == matchFights.size() - 1;
            if (lastFight) {
                Json.add(st, "wins", 1);
                if (!bool(st, "completed")) {
                    st.put("completed", true);
                    List<Object> items = new ArrayList<Object>();
                    for (Object loot : getList(match, "completionLoot")) {
                        items.addAll(Rewards.grantLoot(svc, u, map(loot), 1));
                    }
                    if (!items.isEmpty()) {
                        bundles.add(Rewards.bundle("Match", str(match, "guid"), items));
                    }
                    if (!event.isEmpty()) {
                        Events.onEventMatchCompleted(ctx, event, match, bundles);
                    } else {
                        checkChapterAndAct(ctx, match, bundles);
                    }
                }
                st.put("enemies", arr()); // fresh opponents next time
            }
        }
        bundles.add(0, Rewards.bundle("Fight", fid, fightItems));
        ctx.save();
        return data(obj("rewards", bundles, "matchChallengesResults", challengeBits & 0xFF, "publicReplayId", ""));
    }

    private static boolean done(Map<String, Object> camp, Map<String, Object> m) {
        return bool(getMap(camp, str(m, "guid")), "completed");
    }

    private static boolean full(Content c, Map<String, Object> camp, Map<String, Object> m) {
        int n = 0;
        for (Map<String, Object> f : c.fightsOf(m)) {
            n += getList(f, "matchChallenges").size();
        }
        return done(camp, m) && Long.bitCount(lng(getMap(camp, str(m, "guid")), "challenges", 0)) >= n;
    }

    private static void grantCompletion(Ctx ctx, Map<String, Object> flags, String flagKey, boolean ok,
                                        Map<String, Object> holder, String lootKey, String origin, List<Object> bundles) {
        if (!ok || bool(flags, flagKey)) {
            return;
        }
        flags.put(flagKey, true);
        List<Object> items = new ArrayList<Object>();
        for (Object loot : getList(holder, lootKey)) {
            items.addAll(Rewards.grantLoot(ctx.players(), ctx.user, map(loot), 1));
        }
        if (!items.isEmpty()) {
            bundles.add(Rewards.bundle(origin, str(holder, "guid"), items));
        }
    }

    private static void checkChapterAndAct(Ctx ctx, Map<String, Object> match, List<Object> bundles) {
        Content c = ctx.content;
        Map<String, Object> camp = child(ctx.user, "campaign");
        Map<String, Object> chapter = c.matchChapter.get(str(match, "guid"));
        if (chapter == null) {
            return;
        }
        Map<String, Object> flags = child(ctx.user, "claimed");
        boolean allDone = true;
        boolean allFull = true;
        for (Map<String, Object> m : c.matchesOf(chapter)) {
            allDone &= done(camp, m);
            allFull &= full(c, camp, m);
        }
        String cg = str(chapter, "guid");
        grantCompletion(ctx, flags, "chapter:" + cg, allDone, chapter, "completionLoots", "Chapter", bundles);
        grantCompletion(ctx, flags, "chapterfull:" + cg, allFull, chapter, "fullCompletionLoots", "FullChapter", bundles);
        Map<String, Object> act = c.chapterAct.get(cg);
        if (act == null) {
            return;
        }
        allDone = true;
        allFull = true;
        for (Map<String, Object> ch : c.chaptersOf(act)) {
            for (Map<String, Object> m : c.matchesOf(ch)) {
                allDone &= done(camp, m);
                allFull &= full(c, camp, m);
            }
        }
        String ag = str(act, "guid");
        grantCompletion(ctx, flags, "act:" + ag, allDone, act, "completionLoots", "Act", bundles);
        grantCompletion(ctx, flags, "actfull:" + ag, allFull, act, "fullCompletionLoots", "FullAct", bundles);
    }
}

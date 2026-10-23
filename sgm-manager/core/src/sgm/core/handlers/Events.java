package sgm.core.handlers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sgm.core.Content;
import sgm.core.Ctx;
import sgm.core.Json;
import sgm.core.Module;
import sgm.core.Players;
import sgm.core.Rewards;
import sgm.core.Server;

import static sgm.core.Json.arr;
import static sgm.core.Json.bool;
import static sgm.core.Json.child;
import static sgm.core.Json.getList;
import static sgm.core.Json.getMap;
import static sgm.core.Json.lng;
import static sgm.core.Json.map;
import static sgm.core.Json.obj;
import static sgm.core.Json.str;

/**
 * Limited-Time-Act (LTA) events: the rotating PvE missions. Each EventSet groups the difficulty
 * variants of one LTA (LTAEventData -&gt; one Act). A configurable number of sets rotates every UTC
 * day (or all run at once). Entering costs EconomySettings.playDailyEventPrice, each completed run
 * counts toward LTAEventData.maxPlaysAllowed, and progress resets between runs.
 */
public final class Events extends Module {
    static final long DAY_MS = 86400000L;
    static final String TICKET = "DailyEventTicket";

    public Events(Server app) {
        super(app);
        route("GET", "events/{region:[^/]*}", "get_events");
        route("PUT", "events/{iid}/enter", "enter_event");
        route("POST", "events/{iid}/skip", "skip_event");
        route("PUT", "events/claimFreeEventTickets/{kind}", "claim_free_tickets");
    }

    /** One active event occurrence. */
    public static final class Active {
        public final Map<String, Object> ev;
        public final String iid;
        public final long start;
        public final long end;

        Active(Map<String, Object> ev, String iid, long start, long end) {
            this.ev = ev;
            this.iid = iid;
            this.start = start;
            this.end = end;
        }
    }

    @Override
    protected Object call(String name, Ctx ctx, Map<String, String> p) {
        if (name.equals("get_events")) {
            String want = ctx.q("type");
            if (want != null && !want.isEmpty() && !want.equals("None") && !want.equals("LimitedTimeActs")) {
                return data(obj("activeEvents", arr(), "upcomingEvents", arr()));
            }
            List<Object> active = new ArrayList<Object>();
            for (Active a : activeEvents(ctx, 0)) {
                active.add(eventData(ctx, a));
            }
            List<Object> upcoming = new ArrayList<Object>();
            if (!"all".equals(app.cfgS("lta_events", "rotate"))) {
                for (Active a : activeEvents(ctx, 1)) {
                    upcoming.add(eventData(ctx, a));
                }
            }
            return data(obj("activeEvents", active, "upcomingEvents", upcoming));
        } else if (name.equals("enter_event")) {
            return enter(ctx, p.get("iid"));
        } else if (name.equals("skip_event")) {
            return skip(ctx, p.get("iid"));
        } else if (name.equals("claim_free_tickets")) {
            return claimFreeTickets(ctx);
        }
        throw new IllegalStateException(name);
    }

    static List<Object[]> ltaSets(Content c) {
        List<Object[]> sets = new ArrayList<Object[]>();
        for (Map<String, Object> es : c.all("EventSet")) {
            List<Map<String, Object>> evs = new ArrayList<Map<String, Object>>();
            for (Object r : getList(es, "contentDatas")) {
                Map<String, Object> e = c.get(r);
                if (e != null && "LTAEventData".equals(e.get("_class")) && c.get(e.get("act")) != null) {
                    evs.add(e);
                }
            }
            if (!evs.isEmpty()) {
                sets.add(new Object[]{es, evs});
            }
        }
        Collections.sort(sets, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return str(map(a[0]), "_name").compareTo(str(map(b[0]), "_name"));
            }
        });
        return sets;
    }

    /** Active events for the given day (0 = today, 1 = tomorrow). */
    @SuppressWarnings("unchecked")
    public static List<Active> activeEvents(Ctx ctx, int dayOffset) {
        List<Object[]> sets = ltaSets(ctx.content);
        List<Active> out = new ArrayList<Active>();
        if (sets.isEmpty()) {
            return out;
        }
        long day = (ctx.nowMs / DAY_MS) + dayOffset;
        List<Object[]> chosen;
        long start;
        long end;
        String tag;
        if ("all".equals(ctx.app.cfgS("lta_events", "rotate"))) {
            long week = (day / 7);
            start = week * 7 * DAY_MS;
            end = (week + 1) * 7 * DAY_MS;
            chosen = sets;
            tag = "w" + week;
        } else {
            int k = (int) Math.max(1, ctx.app.cfgL("lta_sets_per_day", 3));
            int first = (int) ((day * k) % sets.size());
            chosen = new ArrayList<Object[]>();
            for (int n = 0; n < Math.min(k, sets.size()); n++) {
                chosen.add(sets.get((first + n) % sets.size()));
            }
            start = day * DAY_MS;
            end = (day + 1) * DAY_MS;
            tag = "d" + day;
        }
        for (Object[] s : chosen) {
            for (Map<String, Object> ev : (List<Map<String, Object>>) s[1]) {
                out.add(new Active(ev, "lta-" + str(ev, "guid") + "-" + tag, start, end));
            }
        }
        return out;
    }

    static Active findEvent(Ctx ctx, String iid) {
        for (Active a : activeEvents(ctx, 0)) {
            if (a.iid.equals(iid)) {
                return a;
            }
        }
        return null;
    }

    static Map<String, Object> eventRecord(Ctx ctx, String iid) {
        Map<String, Object> events = child(ctx.user, "events");
        Map<String, Object> rec = getMap(events, iid);
        if (rec == null) {
            rec = obj("entered", false, "playCount", 0L, "progress", new LinkedHashMap<String, Object>());
            events.put(iid, rec);
        }
        return rec;
    }

    static Map<String, Object> eventData(Ctx ctx, Active a) {
        Map<String, Object> rec = getMap(child(ctx.user, "events"), a.iid);
        Map<String, Object> act = ctx.content.get(a.ev.get("act"));
        return obj("type", "LimitedTimeAct", "guid", a.ev.get("guid"), "eventInstanceId", a.iid,
                "hasEntered", bool(rec, "entered"), "scheduleInstance", obj("startDate", a.start, "endDate", a.end),
                "actGuid", act.get("guid"), "playCount", lng(rec, "playCount", 0),
                "wasEverFullyCompleted", bool(child(ctx.user, "ltaCompleted"), str(a.ev, "guid")));
    }

    static Map<String, Long> ticketPrice(Ctx ctx, String key) {
        Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Object o : getList(getMap(ctx.content.economy(), key), "currencies")) {
            Map<String, Object> cur = map(o);
            String nm = Players.CURRENCY_TYPES.get((int) lng(cur, "currencyType", 0));
            Long prev = out.get(nm);
            out.put(nm, (prev == null ? 0 : prev) + lng(cur, "amount", 0));
        }
        return out;
    }

    private Object enter(Ctx ctx, String iid) {
        Active a = findEvent(ctx, iid);
        if (a == null) {
            throw err(404, "EventNotFound", "event is not active");
        }
        Map<String, Object> rec = eventRecord(ctx, iid);
        if (bool(rec, "entered")) {
            return empty();
        }
        if (lng(rec, "playCount", 0) >= lng(a.ev, "maxPlaysAllowed", 1)) {
            throw err(400, "EventMaxPlays", "no plays left");
        }
        if (!ctx.players().spend(ctx.user, ticketPrice(ctx, "playDailyEventPrice"))) {
            throw err(400, "NotEnoughCurrency", "not enough daily event tickets");
        }
        rec.put("entered", true);
        rec.put("progress", new LinkedHashMap<String, Object>());
        ctx.save();
        return empty();
    }

    private static void finishRun(Ctx ctx, Map<String, Object> ev, String iid, List<Object> bundles) {
        Map<String, Object> u = ctx.user;
        Map<String, Object> rec = eventRecord(ctx, iid);
        Json.add(rec, "playCount", 1);
        rec.put("entered", false);
        rec.put("progress", new LinkedHashMap<String, Object>());
        Map<String, Object> done = child(u, "ltaCompleted");
        List<Object> items = new ArrayList<Object>();
        if (!bool(done, str(ev, "guid"))) {
            done.put(str(ev, "guid"), true);
            for (Object loot : getList(ev, "firstTimeCompletionLoots")) {
                items.addAll(Rewards.grantLoot(ctx.players(), u, map(loot), 1));
            }
        }
        Map<String, Object> drop = Rewards.pickWeighted(ctx.players(), getList(ev, "randomDropLoots"));
        if (drop != null) {
            items.addAll(Rewards.grantLoot(ctx.players(), u, drop, 1));
        }
        if (!items.isEmpty()) {
            bundles.add(Rewards.bundle("Act", str(ev, "guid"), items));
        }
    }

    /** Called by the fight handler the first time a match of an entered event run is completed. */
    static void onEventMatchCompleted(Ctx ctx, String iid, Map<String, Object> match, List<Object> bundles) {
        Active a = findEvent(ctx, iid);
        if (a == null) {
            return;
        }
        Map<String, Object> act = ctx.content.get(a.ev.get("act"));
        Map<String, Object> progress = child(eventRecord(ctx, iid), "progress");
        for (Map<String, Object> ch : ctx.content.chaptersOf(act)) {
            for (Map<String, Object> m : ctx.content.matchesOf(ch)) {
                if (!bool(getMap(progress, str(m, "guid")), "completed")) {
                    return;
                }
            }
        }
        finishRun(ctx, a.ev, iid, bundles);
    }

    private Object skip(Ctx ctx, String iid) {
        Active a = findEvent(ctx, iid);
        if (a == null || lng(a.ev, "isSkippable", 1) == 0) {
            throw err(400, "EventNotSkippable", "cannot skip");
        }
        Map<String, Object> rec = eventRecord(ctx, iid);
        if (lng(rec, "playCount", 0) >= lng(a.ev, "maxPlaysAllowed", 1)) {
            throw err(400, "EventMaxPlays", "no plays left");
        }
        if (!bool(child(ctx.user, "ltaCompleted"), str(a.ev, "guid"))) {
            throw err(400, "EventNotCompleted", "complete the event once before skipping");
        }
        if (!bool(rec, "entered") && !ctx.players().spend(ctx.user, ticketPrice(ctx, "skipDailyEventPrice"))) {
            throw err(400, "NotEnoughCurrency", "not enough daily event tickets");
        }
        List<Object> bundles = new ArrayList<Object>();
        // a skip pays out what a full run's matches would have, plus the random drop
        Map<String, Object> act = ctx.content.get(a.ev.get("act"));
        List<Object> items = new ArrayList<Object>();
        for (Map<String, Object> ch : ctx.content.chaptersOf(act)) {
            for (Map<String, Object> m : ctx.content.matchesOf(ch)) {
                for (Object loot : getList(m, "completionLoot")) {
                    items.addAll(Rewards.grantLoot(ctx.players(), ctx.user, map(loot), 1));
                }
            }
        }
        if (!items.isEmpty()) {
            bundles.add(Rewards.bundle("Match", str(act, "guid"), items));
        }
        finishRun(ctx, a.ev, iid, bundles);
        ctx.save();
        return data(bundles);
    }

    private Object claimFreeTickets(Ctx ctx) {
        Map<String, Object> u = ctx.user;
        Map<String, Object> econ = ctx.content.economy();
        long day = (ctx.nowMs / DAY_MS);
        List<Object> items = new ArrayList<Object>();
        if (!u.containsKey("ticketDay") || lng(u, "ticketDay", -1) != day) {
            u.put("ticketDay", day);
            long have = lng(child(u, "currencies"), TICKET, 0);
            long add = Math.max(0, Math.min(lng(econ, "claimableDailyEventTicketsPerDay", 4), lng(econ, "maxDailyEventTickets", 4) - have));
            if (add != 0) {
                ctx.players().addCurrency(u, TICKET, add);
                items.add(Rewards.currencyItem(TICKET, add));
            }
            ctx.save();
        }
        return data(obj("rewardList", items.isEmpty() ? arr() : arr(Rewards.bundle("Undefined", "", items)),
                "freeEventTicketResetDate", (day + 1) * DAY_MS));
    }
}

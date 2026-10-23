package sgm.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import sgm.core.handlers.Campaign;
import sgm.core.handlers.Progression;
import sgm.core.handlers.StoreFront;

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
 * Account editor: JSON API under /admin/api/ plus the single-page editor at /admin/. Requests need
 * the admin token (query ?token= once, then a cookie) so other apps on the phone cannot edit saves.
 */
public final class Admin implements HttpServer.Handler {
    /** Loads the editor page (classpath on desktop, APK assets on Android). */
    public interface Assets {
        byte[] read(String name) throws IOException;
    }

    private final Server s;
    private final File dataDir;
    public final String token;
    private final Assets assets;
    private final long started = System.currentTimeMillis();
    private Map<String, Object> catalog;

    public Admin(Server s, File dataDir, Assets assets) throws IOException {
        this.s = s;
        this.dataDir = dataDir;
        this.assets = assets != null ? assets : new Assets() {
            @Override
            public byte[] read(String name) throws IOException {
                InputStream in = Admin.class.getResourceAsStream("/sgm/web/" + name);
                if (in == null) {
                    throw new IOException("missing resource " + name);
                }
                return readAll(in);
            }
        };
        File tf = new File(dataDir, "admin-token");
        if (tf.isFile()) {
            token = new String(Tls.readAll(tf), Protocol.UTF8).trim();
        } else {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                sb.append(String.format("%02x", x & 0xff));
            }
            token = sb.toString();
            Store.writeAtomic(tf, token);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    // ---- HTTP ---------------------------------------------------------------------------------

    @Override
    public HttpServer.Response handle(HttpServer.Request req) throws Exception {
        String path = req.path();
        Map<String, List<String>> q = Server.parseQuery(req.query());
        List<String> t = q.get("token");
        boolean authed = token.equals(Server.cookie(req.headers.get("Cookie"), "sgmadmin"))
                || token.equals(req.headers.get("X-Admin-Token"));
        if (t != null && token.equals(t.get(0))) {
            HttpServer.Response r = new HttpServer.Response(302, new byte[0]);
            r.headers.put("Set-Cookie", "sgmadmin=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            r.headers.put("Location", "/admin/");
            return r;
        }
        if (!authed) {
            return HttpServer.Response.text(403, "text/plain; charset=utf-8",
                    "Open the editor from the SGM Manager app (or use the URL with ?token= printed by the server).");
        }
        if (path.equals("/admin") || path.equals("/admin/") || path.equals("/")) {
            HttpServer.Response r = new HttpServer.Response(200, assets.read("admin.html"));
            r.headers.put("Content-Type", "text/html; charset=utf-8");
            r.headers.put("Cache-Control", "no-store");
            return r;
        }
        if (!path.startsWith("/admin/api/")) {
            return HttpServer.Response.json(404, obj("error", "not found"));
        }
        String[] parts = path.substring("/admin/api/".length()).split("/");
        Object body = null;
        if (req.body.length > 0) {
            try {
                body = Json.parse(new String(req.body, Protocol.UTF8));
            } catch (RuntimeException e) {
                return HttpServer.Response.json(400, obj("error", "body is not valid JSON: " + e.getMessage()));
            }
        }
        try {
            synchronized (s.lock) {
                return api(req.method, parts, body);
            }
        } catch (Protocol.ApiError e) {
            return HttpServer.Response.json(e.status, obj("error", e.getMessage()));
        } catch (RuntimeException e) {
            Log.e("admin " + req.method + " " + path + " failed", e);
            return HttpServer.Response.json(500, obj("error", String.valueOf(e)));
        }
    }

    private static Protocol.ApiError bad(String msg) {
        return new Protocol.ApiError(400, "BadRequest", msg);
    }

    private HttpServer.Response api(String method, String[] p, Object body) {
        String what = p[0];
        if (what.equals("status") && method.equals("GET")) {
            return ok(status());
        } else if (what.equals("catalog") && method.equals("GET")) {
            return ok(catalog());
        } else if (what.equals("logs") && method.equals("GET")) {
            return ok(obj("lines", Log.recent()));
        } else if (what.equals("config")) {
            if (method.equals("PUT")) {
                Map<String, Object> m = map(body);
                if (m == null) {
                    throw bad("expected an object");
                }
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    if (e.getValue() == null) {
                        s.config.remove(e.getKey());
                    } else {
                        s.config.put(e.getKey(), e.getValue());
                    }
                }
                saveConfig();
            }
            return ok(obj("config", s.config, "defaults", Server.defaultConfig()));
        } else if (what.equals("users")) {
            if (p.length == 1) {
                if (method.equals("POST")) {
                    Map<String, Object> m = map(body);
                    Map<String, Object> u;
                    if (m != null && map(m.get("import")) != null) {
                        u = sanitizeImport(map(m.get("import")));
                        u.put("id", Players.newId());
                        u.put("guestToken", "");
                    } else {
                        u = s.players.create(null, m == null ? null : (String) m.get("handle"));
                    }
                    s.store.saveUser(u);
                    return ok(view(u));
                }
                return ok(obj("users", userList(), "bindings", s.store.bindings()));
            }
            String id = p[1];
            Map<String, Object> u = s.store.loadUser(id);
            if (u == null) {
                throw new Protocol.ApiError(404, "NotFound", "no such user");
            }
            String sub = p.length > 2 ? p[2] : "";
            if (sub.isEmpty()) {
                if (method.equals("DELETE")) {
                    s.store.deleteUser(id);
                    return ok(obj("deleted", id));
                } else if (method.equals("PUT")) {
                    Map<String, Object> doc = sanitizeImport(map(body));
                    keepIdentity(u, doc);
                    s.store.saveUser(doc);
                    return ok(view(doc));
                }
                return ok(view(u));
            } else if (sub.equals("export")) {
                HttpServer.Response r = HttpServer.Response.json(200, u);
                r.headers.put("Content-Disposition", "attachment; filename=\"sgm-save-" + str(u, "handle").replaceAll("[^A-Za-z0-9_-]", "_") + ".json\"");
                return r;
            } else if (sub.equals("activate") && method.equals("POST")) {
                s.store.makeActive(id);
                return ok(obj("active", id));
            } else if (sub.equals("ops") && method.equals("POST")) {
                Map<String, Object> m = map(body);
                List<Object> ops = m == null ? null : Json.list(m.get("ops"));
                if (ops == null) {
                    throw bad("expected {\"ops\": [...]}");
                }
                List<Object> results = new ArrayList<Object>();
                for (Object op : ops) {
                    results.add(applyOp(u, map(op)));
                }
                s.store.saveUser(u);
                Map<String, Object> v = view(u);
                v.put("results", results);
                return ok(v);
            }
        }
        throw new Protocol.ApiError(404, "NotFound", "unknown admin endpoint");
    }

    private static HttpServer.Response ok(Object o) {
        HttpServer.Response r = HttpServer.Response.json(200, o);
        r.headers.put("Cache-Control", "no-store");
        return r;
    }

    private void saveConfig() {
        if (dataDir != null) {
            Map<String, Object> defaults = Server.defaultConfig();
            Map<String, Object> diff = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : s.config.entrySet()) {
                if (!Json.write(e.getValue()).equals(Json.write(defaults.get(e.getKey())))) {
                    diff.put(e.getKey(), e.getValue());
                }
            }
            Store.writeAtomic(new File(dataDir, "config.json"), Json.write(diff));
        }
    }

    /** Overrides saved by the editor (data/config.json), applied on top of the defaults at startup. */
    public static Map<String, Object> loadConfig(File dataDir) {
        File f = new File(dataDir, "config.json");
        if (!f.isFile()) {
            return null;
        }
        try {
            return map(Json.parse(new String(Tls.readAll(f), Protocol.UTF8)));
        } catch (Exception e) {
            Log.w("ignoring unreadable " + f + ": " + e);
            return null;
        }
    }

    // ---- views ---------------------------------------------------------------------------------

    private Map<String, Object> status() {
        return obj("users", (long) s.store.allUserIds().size(), "unhandled", new TreeMap<String, Integer>(s.unhandled),
                "uptimeMs", System.currentTimeMillis() - started, "contentObjects", (long) s.content.objs.size(),
                "hasLocalization", !s.content.all("Localization").isEmpty());
    }

    private List<Object> userList() {
        List<Object> out = new ArrayList<Object>();
        Map<String, Object> b = s.store.bindings();
        Set<Object> active = new HashSet<Object>(getMap(b, "devices").values());
        active.addAll(getMap(b, "guestTokens").values());
        for (String id : s.store.allUserIds()) {
            Map<String, Object> u = s.store.loadUser(id);
            if (u == null) {
                continue;
            }
            out.add(obj("id", id, "handle", u.get("handle"), "level", (long) s.players.level(u),
                    "characters", (long) child(u, "characters").size(), "created", u.get("created"),
                    "active", active.contains(id)));
        }
        return out;
    }

    private Map<String, Object> view(Map<String, Object> u) {
        Map<String, Object> chars = new LinkedHashMap<String, Object>();
        for (Object o : child(u, "characters").values()) {
            Map<String, Object> ch = map(o);
            int[] st = s.players.charStats(ch);
            chars.put(str(ch, "id"), obj("level", (long) st[0], "levelCap", (long) Formulas.levelCap(i(ch, "tier")),
                    "lifebar", (long) st[1], "attack", (long) st[2], "fighterScore", (long) st[3]));
        }
        int lvl = s.players.level(u);
        return obj("save", u, "derived", obj("level", (long) lvl, "xpForNext", s.formulas.playerLadder.xpForLevel(lvl + 1, false),
                "characters", chars, "topTeamPower", s.players.topTeamPower(u)));
    }

    /** Validates an uploaded save and fills in missing containers. */
    private Map<String, Object> sanitizeImport(Map<String, Object> doc) {
        if (doc == null) {
            throw bad("expected a save object");
        }
        doc = map(Json.copy(doc));
        if (doc.containsKey("save") && map(doc.get("save")) != null) {
            doc = map(doc.get("save")); // accept the editor's own view format too
        }
        Map<String, Object> fresh = s.players.create(null, "x");
        for (Map.Entry<String, Object> e : fresh.entrySet()) {
            Object have = doc.get(e.getKey());
            if (have == null || (e.getValue() instanceof Map && !(have instanceof Map)) || (e.getValue() instanceof List && !(have instanceof List))) {
                doc.put(e.getKey(), e.getValue() instanceof Map || e.getValue() instanceof List ? Json.copy(emptyLike(e.getValue())) : e.getValue());
            }
        }
        for (Object o : child(doc, "characters").values()) {
            if (map(o) == null || s.content.guid(str(map(o), "variant"), "VariantCharacterData") == null) {
                throw bad("character with unknown variant: " + Json.write(o));
            }
        }
        return doc;
    }

    private static Object emptyLike(Object v) {
        return v instanceof Map ? new LinkedHashMap<String, Object>() : new ArrayList<Object>();
    }

    private static void keepIdentity(Map<String, Object> from, Map<String, Object> to) {
        for (String k : new String[]{"id", "guestToken", "deviceIds"}) {
            if (from.containsKey(k)) {
                to.put(k, from.get(k));
            }
        }
    }

    // ---- edit operations -------------------------------------------------------------------------

    private Ctx ctxFor(Map<String, Object> u) {
        Ctx c = new Ctx(s, "ADMIN", "", new HashMap<String, List<String>>(), new LinkedHashMap<String, Object>(),
                new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER));
        c.user = u;
        return c;
    }

    private Map<String, Object> character(Map<String, Object> u, Map<String, Object> op) {
        Map<String, Object> ch = getMap(child(u, "characters"), str(op, "id"));
        if (ch == null) {
            throw bad("unknown character " + str(op, "id"));
        }
        return ch;
    }

    private void setCharLevel(Map<String, Object> ch, int level) {
        Formulas.Ladder lad = s.formulas.charLadder(i(ch, "tier"));
        ch.put("xp", lad.xpForLevel(Math.max(1, Math.min(level, lad.cap)), bool(ch, "shiny")));
    }

    /** Buys every reachable node of the character's tree for free (respecting branch exclusivity). */
    private int maxNodes(Map<String, Object> u, Map<String, Object> ch) {
        Ctx ctx = ctxFor(u);
        Map<String, Object> tree = Progression.tree(ctx, ch);
        int added = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Object ref : getList(tree, "purchaseOrder")) {
                Map<String, Object> n = s.content.get(ref);
                if (n != null && !truthy(n, "isHidden") && Progression.canBuy(ctx, ch, n)) {
                    childList(ch, "nodes").add(n.get("guid"));
                    added++;
                    changed = true;
                }
            }
        }
        return added;
    }

    private Object applyOp(Map<String, Object> u, Map<String, Object> op) {
        if (op == null) {
            throw bad("operation must be an object");
        }
        String name = str(op, "op");
        Players P = s.players;
        if (name.equals("set_currency")) {
            child(u, "currencies").put(str(op, "name"), Math.max(0, lng(op, "amount", 0)));
        } else if (name.equals("set_handle")) {
            String h = str(op, "handle").trim();
            if (h.isEmpty() || h.length() > 20) {
                throw bad("handle must be 1-20 characters");
            }
            u.put("handle", h);
        } else if (name.equals("set_player_level")) {
            int lvl = Math.max(1, Math.min(i(op, "level", 1), s.formulas.playerLadder.cap));
            u.put("xp", s.formulas.playerLadder.xpForLevel(lvl, false));
        } else if (name.equals("set_player_xp")) {
            u.put("xp", Math.max(0, lng(op, "xp", 0)));
        } else if (name.equals("add_character")) {
            Map<String, Object> v = s.content.guid(str(op, "variant"), "VariantCharacterData");
            if (v == null) {
                throw bad("unknown variant " + str(op, "variant"));
            }
            List<Object> ids = new ArrayList<Object>();
            int count = Math.max(1, Math.min(i(op, "count", 1), 100));
            for (int k = 0; k < count; k++) {
                Integer tier = op.get("tier") == null ? null : Math.max(0, Math.min(i(op, "tier"), 3));
                Map<String, Object> ch = P.grantCharacter(u, str(v, "guid"), tier, 0, bool(op, "shiny"));
                ch.put("new", false);
                if (op.get("level") != null) {
                    setCharLevel(ch, "max".equals(op.get("level")) ? 99 : i(op, "level", 1));
                }
                if (bool(op, "maxNodes")) {
                    maxNodes(u, ch);
                }
                ids.add(ch.get("id"));
            }
            return obj("added", ids);
        } else if (name.equals("edit_character")) {
            Map<String, Object> ch = character(u, op);
            int level = P.charLevel(ch);
            if (op.get("shiny") != null) {
                ch.put("shiny", bool(op, "shiny"));
            }
            if (op.get("tier") != null) {
                ch.put("tier", (long) Math.max(0, Math.min(i(op, "tier"), 3)));
            }
            if (op.get("level") != null) {
                level = "max".equals(op.get("level")) ? 99 : i(op, "level", level);
            }
            setCharLevel(ch, level);
            if (op.get("locked") != null) {
                ch.put("locked", bool(op, "locked"));
            }
            if (op.get("prestige") != null) {
                ch.put("prestige", Math.max(0, Math.min(lng(op, "prestige", 0), 100)));
            }
            if (op.get("superLevels") != null) {
                List<Object> lv = new ArrayList<Object>();
                for (Object x : getList(op, "superLevels")) {
                    lv.add(Math.max(0, Math.min(((Number) x).longValue(), 10)));
                }
                ch.put("superLevels", lv);
            }
            if (op.get("superFeature") != null) {
                ch.put("superFeature", Math.max(0, lng(op, "superFeature", 0)));
            }
            Object nodes = op.get("nodes");
            if ("none".equals(nodes)) {
                ch.put("nodes", arr());
            } else if ("all".equals(nodes)) {
                maxNodes(u, ch);
            } else if (nodes instanceof List) {
                List<Object> keep = new ArrayList<Object>();
                for (Object g : (List<?>) nodes) {
                    if (s.content.guid(String.valueOf(g), "SkillTreeNode") != null) {
                        keep.add(g);
                    }
                }
                ch.put("nodes", keep);
            }
            if (bool(op, "refillEnergy")) {
                ch.put("energy", (long) P.maxEnergy);
                ch.put("energyTs", 0L);
            }
        } else if (name.equals("remove_character")) {
            Map<String, Object> ch = character(u, op);
            child(u, "characters").remove(ch.get("id"));
            childList(u, "team").remove(ch.get("id"));
            childList(u, "defenseTeam").remove(ch.get("id"));
        } else if (name.equals("set_team")) {
            List<Object> team = new ArrayList<Object>();
            for (Object id : getList(op, "ids")) {
                if (child(u, "characters").containsKey(id) && team.size() < 3 && !team.contains(id)) {
                    team.add(id);
                }
            }
            u.put("team", team);
        } else if (name.equals("add_move")) {
            Map<String, Object> g = s.content.guid(str(op, "guid"));
            if (g == null || !String.valueOf(g.get("_class")).endsWith("MoveData")) {
                throw bad("unknown move " + str(op, "guid"));
            }
            String pool = "AssistMoveData".equals(g.get("_class")) ? "assistMoves" : "moves";
            List<Object> ids = new ArrayList<Object>();
            for (int k = 0; k < Math.max(1, Math.min(i(op, "count", 1), 100)); k++) {
                Map<String, Object> mv = P.grantMove(u, pool, str(g, "guid"), false);
                mv.put("evolution", Math.max(1, Math.min(lng(op, "evolution", 1), 15)));
                ids.add(mv.get("id"));
            }
            return obj("added", ids);
        } else if (name.equals("edit_move") || name.equals("remove_move")) {
            String id = str(op, "id");
            Map<String, Object> pool = child(u, "moves").containsKey(id) ? child(u, "moves") : child(u, "assistMoves");
            Map<String, Object> mv = getMap(pool, id);
            if (mv == null) {
                throw bad("unknown move " + id);
            }
            if (name.equals("remove_move")) {
                pool.remove(id);
                for (Object o : child(u, "characters").values()) {
                    childList(map(o), "moves").remove(id);
                    if (id.equals(map(o).get("assist"))) {
                        map(o).put("assist", "");
                    }
                }
            } else {
                if (op.get("evolution") != null) {
                    mv.put("evolution", Math.max(1, Math.min(lng(op, "evolution", 1), 15)));
                }
                if (op.get("locked") != null) {
                    mv.put("locked", bool(op, "locked"));
                }
            }
        } else if (name.equals("set_consumable") || name.equals("set_relic")) {
            boolean relic = name.equals("set_relic");
            Map<String, Object> g = s.content.guid(str(op, "guid"), relic ? "GachaData" : "ConsumableData");
            if (g == null) {
                throw bad("unknown " + (relic ? "relic " : "consumable ") + str(op, "guid"));
            }
            child(u, relic ? "gachas" : "consumables").put(str(g, "guid"), Math.max(0, lng(op, "count", 0)));
        } else if (name.equals("set_skill_points") || name.equals("set_reroll_runes")) {
            Map<String, Object> b = s.content.guid(str(op, "base"), "BaseCharacterData");
            if (b == null) {
                throw bad("unknown base character " + str(op, "base"));
            }
            child(u, name.equals("set_skill_points") ? "skillPoints" : "rerollRunes").put(str(b, "guid"), Math.max(0, lng(op, "amount", 0)));
        } else if (name.equals("add_node_modifier")) {
            Map<String, Object> g = s.content.guid(str(op, "guid"), "CollectibleNodeModifierData");
            if (g == null) {
                throw bad("unknown catalyst " + str(op, "guid"));
            }
            String nid = Players.newId();
            String el = str(op, "element", "Neutral");
            child(u, "nodeModifiers").put(nid, obj("id", nid, "guid", g.get("guid"), "level", 1L, "element", el, "new", true));
            return obj("added", arr(nid));
        } else if (name.equals("remove_node_modifier")) {
            child(u, "nodeModifiers").remove(str(op, "id"));
        } else if (name.equals("complete_tutorials")) {
            P.completeAllTutorials(u);
        } else if (name.equals("refill_energy")) {
            for (Object o : child(u, "characters").values()) {
                map(o).put("energy", (long) P.maxEnergy);
                map(o).put("energyTs", 0L);
            }
        } else if (name.equals("max_all_characters")) {
            for (Object o : child(u, "characters").values()) {
                setCharLevel(map(o), 99);
                maxNodes(u, map(o));
            }
        } else if (name.equals("unlock_all_characters")) {
            return obj("added", unlockAll(u, op));
        } else if (name.equals("campaign")) {
            return obj("matches", campaign(u, op));
        } else if (name.equals("reset_daily")) {
            u.remove("daily");
            u.remove("ticketDay");
        } else if (name.equals("reset_events")) {
            u.remove("events");
            if (bool(op, "firstClears")) {
                u.remove("ltaCompleted");
            }
        } else {
            throw bad("unknown op " + name);
        }
        return obj("ok", true);
    }

    private List<Object> unlockAll(Map<String, Object> u, Map<String, Object> op) {
        Set<Object> owned = new HashSet<Object>();
        for (Object o : child(u, "characters").values()) {
            owned.add(map(o).get("variant"));
        }
        List<Object> added = new ArrayList<Object>();
        for (Map<String, Object> v : s.content.all("VariantCharacterData")) {
            if (!collectible(v) || owned.contains(v.get("guid"))) {
                continue;
            }
            Integer tier = "max".equals(op.get("tier")) ? Integer.valueOf(3) : null;
            Map<String, Object> ch = s.players.grantCharacter(u, str(v, "guid"), tier, 0, bool(op, "shiny"));
            ch.put("new", false);
            if (op.get("level") != null) {
                setCharLevel(ch, "max".equals(op.get("level")) ? 99 : i(op, "level", 1));
            }
            if (bool(op, "maxNodes")) {
                maxNodes(u, ch);
            }
            added.add(ch.get("id"));
        }
        return added;
    }

    static boolean collectible(Map<String, Object> v) {
        return lng(v, "placeholder", 0) == 0 && lng(v, "isInCompetitiveCollection", 0) == 0
                && lng(v, "variantIndex", -1) >= 0 && lng(v, "isRaidBoss", 0) == 0;
    }

    /** op.action: "complete" (no rewards are granted) or "reset"; op.act: one act guid or all story acts. */
    private long campaign(Map<String, Object> u, Map<String, Object> op) {
        boolean complete = !"reset".equals(str(op, "action"));
        List<Map<String, Object>> acts = new ArrayList<Map<String, Object>>();
        String only = str(op, "act");
        for (Map<String, Object> a : Campaign.campaignActs(s.content)) {
            if (only.isEmpty() || only.equals(a.get("guid"))) {
                acts.add(a);
            }
        }
        Map<String, Object> camp = child(u, "campaign");
        Map<String, Object> flags = child(u, "claimed");
        long n = 0;
        for (Map<String, Object> act : acts) {
            for (Map<String, Object> ch : s.content.chaptersOf(act)) {
                for (Map<String, Object> m : s.content.matchesOf(ch)) {
                    String g = str(m, "guid");
                    if (complete) {
                        int challenges = 0;
                        for (Map<String, Object> f : s.content.fightsOf(m)) {
                            challenges += getList(f, "matchChallenges").size();
                        }
                        Map<String, Object> st = getMap(camp, g);
                        if (st == null) {
                            st = obj("completed", false, "challenges", 0L, "wins", 0L);
                            camp.put(g, st);
                        }
                        st.put("completed", true);
                        st.put("challenges", (1L << Math.min(8, challenges)) - 1);
                        st.put("wins", Math.max(1, lng(st, "wins", 0)));
                    } else {
                        camp.remove(g);
                    }
                    n++;
                }
                for (String k : new String[]{"chapter:", "chapterfull:"}) {
                    if (complete) {
                        flags.put(k + str(ch, "guid"), true);
                    } else {
                        flags.remove(k + str(ch, "guid"));
                    }
                }
            }
            for (String k : new String[]{"act:", "actfull:"}) {
                if (complete) {
                    flags.put(k + str(act, "guid"), true);
                } else {
                    flags.remove(k + str(act, "guid"));
                }
            }
        }
        return n;
    }

    // ---- catalog for the editor's pickers ----------------------------------------------------------

    private String loc(Map<String, String> strings, Object key, String fallback) {
        String v = key instanceof String ? strings.get(key) : null;
        return v != null && !v.isEmpty() ? v : fallback;
    }

    private synchronized Map<String, Object> catalog() {
        if (catalog != null) {
            return catalog;
        }
        Map<String, String> L = new HashMap<String, String>();
        for (Map<String, Object> l : s.content.all("Localization")) {
            for (Map.Entry<String, Object> e : child(l, "strings").entrySet()) {
                L.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        Content c = s.content;
        Map<String, String> baseNames = new HashMap<String, String>();
        Map<String, String> moveBase = new HashMap<String, String>();
        List<Object> bases = new ArrayList<Object>();
        for (Map<String, Object> b : c.all("BaseCharacterData")) {
            String nm = loc(L, b.get("displayName"), str(b, "_name"));
            baseNames.put(str(b, "_key"), nm);
            bases.add(obj("guid", b.get("guid"), "name", nm));
            for (String k : new String[]{"specialMoves", "blockbusters"}) {
                for (Object r : getList(b, k)) {
                    moveBase.put(String.valueOf(r), str(b, "guid"));
                }
            }
        }
        for (Map<String, Object> a : c.all("AssistCharacterData")) {
            String nm = loc(L, a.get("displayName"), str(a, "_name"));
            for (Object r : getList(a, "assistMoves")) {
                moveBase.put(String.valueOf(r), "assist:" + nm);
            }
        }
        List<Object> variants = new ArrayList<Object>();
        for (Map<String, Object> v : c.all("VariantCharacterData")) {
            Map<String, Object> b = c.get(v.get("baseCharacter"));
            variants.add(obj("guid", v.get("guid"), "name", loc(L, v.get("displayVariantName"), str(v, "_name")),
                    "character", b == null ? "?" : baseNames.get(str(b, "_key")), "base", b == null ? "" : b.get("guid"),
                    "tier", lng(v, "initialTier", 0), "element", lng(v, "elementAffiliation", 0), "collectible", collectible(v),
                    "internal", v.get("_name")));
        }
        List<Object> moves = new ArrayList<Object>();
        for (String cls : new String[]{"SpecialMoveData", "BlockbusterMoveData", "AssistMoveData"}) {
            for (Map<String, Object> m : c.all(cls)) {
                String nm = loc(L, m.get("title"), str(m, "_name"));
                if (cls.equals("AssistMoveData")) {
                    nm = nm + " (" + loc(L, m.get("variantName"), "") + ")";
                }
                String base = moveBase.get(str(m, "_key"));
                moves.add(obj("guid", m.get("guid"), "name", nm, "tier", lng(m, "tier", 0),
                        "kind", cls.equals("SpecialMoveData") ? "special" : cls.equals("AssistMoveData") ? "assist" : "blockbuster",
                        "base", base == null ? "" : base, "internal", m.get("_name")));
            }
        }
        List<Object> consumables = new ArrayList<Object>();
        for (Map<String, Object> m : c.all("ConsumableData")) {
            consumables.add(obj("guid", m.get("guid"), "name", loc(L, m.get("title"), str(m, "_name"))));
        }
        Set<Object> inStore = new HashSet<Object>();
        for (Map<String, Object> g : StoreFront.storeGachas(s)) {
            inStore.add(g.get("guid"));
        }
        List<Object> relics = new ArrayList<Object>();
        for (Map<String, Object> m : c.all("GachaData")) {
            relics.add(obj("guid", m.get("guid"), "name", loc(L, m.get("title"), str(m, "_name")) + " [" + str(m, "_name") + "]",
                    "inStore", inStore.contains(m.get("guid"))));
        }
        List<Object> catalysts = new ArrayList<Object>();
        for (Map<String, Object> m : c.all("CollectibleNodeModifierData")) {
            catalysts.add(obj("guid", m.get("guid"), "name", loc(L, m.get("title"), str(m, "_name")) + " (" + Players.RARITY[Math.max(0, Math.min(i(m, "tier"), 3))] + ")"));
        }
        List<Object> acts = new ArrayList<Object>();
        for (Map<String, Object> a : Campaign.campaignActs(c)) {
            int matches = 0;
            for (Map<String, Object> ch : c.chaptersOf(a)) {
                matches += c.matchesOf(ch).size();
            }
            acts.add(obj("guid", a.get("guid"), "name", a.get("_name"), "matches", (long) matches));
        }
        List<Object> currencies = new ArrayList<Object>();
        currencies.add("SoftCurrency");
        currencies.add("HardCurrency");
        currencies.addAll(Players.OTHER_CURRENCIES);
        currencies.add("GuildOpsPoints");
        Map<String, Object> tutorials = new LinkedHashMap<String, Object>();
        for (int k = 0; k < Players.TUTORIALS.length; k++) {
            tutorials.put(Players.TUTORIALS[k], (long) Players.TUTORIAL_CLEARED[k]);
        }
        List<Object> levelCaps = new ArrayList<Object>();
        for (int cap : Formulas.MAX_CHARACTER_LEVELS) {
            levelCaps.add((long) cap);
        }
        catalog = obj("bases", bases, "variants", variants, "moves", moves, "consumables", consumables, "relics", relics,
                "catalysts", catalysts, "acts", acts, "currencies", currencies, "rarities", Json.arr((Object[]) Players.RARITY),
                "elements", Json.arr((Object[]) Players.ELEMENTS), "levelCaps", levelCaps, "playerLevelCap", (long) s.formulas.playerLadder.cap,
                "tutorials", tutorials, "hasLocalization", !L.isEmpty());
        return catalog;
    }
}

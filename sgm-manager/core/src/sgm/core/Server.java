package sgm.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import sgm.core.handlers.Campaign;
import sgm.core.handlers.Challenges;
import sgm.core.handlers.Core;
import sgm.core.handlers.Events;
import sgm.core.handlers.Frontend;
import sgm.core.handlers.Progression;
import sgm.core.handlers.StoreFront;

/** The game API: routing, sessions, signatures and response encoding for /v1/. */
public final class Server implements HttpServer.Handler {
    public final Content content;
    public final Store store;
    public final Map<String, Object> config;
    public final Formulas formulas;
    public final Players players;
    public final Enemies enemies;
    final List<Module.Route> routes = new ArrayList<Module.Route>();
    public final Map<String, Integer> unhandled = new TreeMap<String, Integer>();
    /** Requests are handled one at a time: the server has a single local player. */
    public final Object lock = new Object();

    public static Map<String, Object> defaultConfig() {
        Map<String, Object> c = new LinkedHashMap<String, Object>();
        // new players skip the tutorial and start with the tutorial's final team
        c.put("skip_tutorials", true);
        c.put("starting_currencies", Json.obj("SoftCurrency", 50000L, "HardCurrency", 500L, "DailyEventTicket", 4L));
        c.put("extra_starting_characters", new ArrayList<Object>());
        c.put("collection_capacity", 1000L);
        // reward tuning (1.0 = values derived from the game data)
        c.put("xp_rate", 1.0);
        c.put("currency_rate", 1.0);
        c.put("player_xp_per_enemy_fs", 0.25);
        c.put("country_code", "US");
        // Limited-Time-Act events: "rotate" (lta_sets_per_day sets each UTC day) or "all"
        c.put("lta_events", "rotate");
        c.put("lta_sets_per_day", 3L);
        // sign-in: password accounts are created on first login
        c.put("auto_register_password_accounts", true);
        c.put("allow_implicit_signup", true);
        return c;
    }

    public Server(Content content, Store store, Map<String, Object> config) {
        this.content = content;
        this.store = store;
        this.config = config;
        this.formulas = new Formulas(content);
        this.players = new Players(content, formulas, config);
        this.enemies = new Enemies(content, formulas);
        new Core(this);
        new Frontend(this);
        new Campaign(this);
        new Events(this);
        new Progression(this);
        new StoreFront(this);
        new Challenges(this);
    }

    public static Server open(File contentPath, File dataDir, Map<String, Object> overrides) throws IOException {
        Map<String, Object> cfg = defaultConfig();
        if (overrides != null) {
            cfg.putAll(overrides);
        }
        return new Server(Content.load(contentPath), new Store(dataDir), cfg);
    }

    // ---- config accessors ----

    public double cfgD(String k, double def) {
        return Json.dbl(config, k, def);
    }

    public long cfgL(String k, long def) {
        return Json.lng(config, k, def);
    }

    public boolean cfgB(String k, boolean def) {
        Object v = config.get(k);
        return v == null ? def : Json.bool(config, k);
    }

    public String cfgS(String k, String def) {
        return Json.str(config, k, def);
    }

    // ---- HTTP ----

    @Override
    public HttpServer.Response handle(HttpServer.Request req) {
        HttpServer.Response r = dispatch(req.method, req.target, req.body, req.headers);
        return r;
    }

    public HttpServer.Response dispatch(String method, String rawPath, byte[] bodyBytes, Map<String, String> headers) {
        int qi = rawPath.indexOf('?');
        String path = percentDecode(qi < 0 ? rawPath : rawPath.substring(0, qi), false);
        if (!path.startsWith(Protocol.API_PREFIX)) {
            return HttpServer.Response.json(404, Json.obj());
        }
        String endpoint = strip(path.substring(Protocol.API_PREFIX.length()), '/');
        Map<String, List<String>> query = parseQuery(qi < 0 ? "" : rawPath.substring(qi + 1));
        String rawBody = bodyBytes == null || bodyBytes.length == 0 ? "" : new String(bodyBytes, Protocol.UTF8);
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        if (!rawBody.trim().isEmpty()) {
            try {
                Object b = Json.parse(rawBody.trim());
                if (b instanceof Map) {
                    body = Json.map(b);
                } else {
                    body.put("_", b);
                }
            } catch (RuntimeException ignored) {
                // not JSON: treat as empty, like the Python server
            }
        }
        Ctx ctx = new Ctx(this, method, endpoint, query, body, headers);

        Module.Route rt = null;
        Map<String, String> params = null;
        for (Module.Route r : routes) {
            params = r.match(method, endpoint);
            if (params != null) {
                rt = r;
                break;
            }
        }

        int status = 200;
        Object payload;
        synchronized (lock) {
            try {
                String token = cookie(headers.get("Cookie"), Protocol.SESSION_COOKIE);
                String userId = token != null ? store.sessionUser(token) : null;
                if (userId != null) {
                    ctx.user = store.loadUser(userId);
                }
                if (rt == null) {
                    String key = method + " " + endpoint;
                    Integer n = unhandled.get(key);
                    unhandled.put(key, n == null ? 1 : n + 1);
                    Log.w("UNHANDLED " + method + " /v1/" + endpoint + " query=" + query + " body=" + abbreviate(rawBody, 300));
                    payload = Json.obj();
                } else {
                    if (!rt.has(Module.NO_AUTH) && ctx.user == null) {
                        throw new Protocol.ApiError(401, "Unauthorized", "no session");
                    }
                    if (rt.has(Module.SIGN)) {
                        checkRequestSignature(endpoint, query, rawBody, headers);
                    }
                    payload = rt.module.call(rt.name, ctx, params);
                    if (payload == null) {
                        payload = Json.obj();
                    }
                    if (ctx.dirty && ctx.user != null) {
                        store.saveUser(ctx.user);
                    }
                }
            } catch (Protocol.ApiError e) {
                status = e.status;
                payload = Json.obj("errorCode", e.code, "message", e.getMessage());
                Log.i("error " + method + " /v1/" + endpoint + " -> " + e.status + " " + e.code);
            } catch (Exception e) {
                status = 500;
                payload = Json.obj("errorCode", "InternalError", "message", "internal server error");
                Log.e("handler crashed for " + method + " /v1/" + endpoint, e);
            }
        }

        String text = Json.write(payload);
        boolean b64 = rt != null && rt.has(Module.B64) && status == 200;
        HttpServer.Response resp = new HttpServer.Response(status,
                b64 ? Protocol.base64(text.getBytes(Protocol.UTF8)).getBytes(Protocol.UTF8) : text.getBytes(Protocol.UTF8));
        if (rt != null && rt.has(Module.SIGN)) {
            resp.headers.put(Protocol.SIG_HEADER, Protocol.responseSignature(text));
        }
        if (ctx.setCookie != null) {
            resp.headers.put("Set-Cookie", ctx.setCookie);
        }
        resp.headers.put("Content-Type", "application/json; charset=utf-8");
        Log.i(method + " /v1/" + endpoint + " -> " + status);
        Log.d("-> " + abbreviate(rawBody, 2000) + "\n<- " + abbreviate(text, 2000));
        return resp;
    }

    private void checkRequestSignature(String endpoint, Map<String, List<String>> query, String rawBody, Map<String, String> headers) {
        String got = headers.get(Protocol.SIG_HEADER);
        if (got == null || got.isEmpty()) {
            Log.i("request to " + endpoint + " carries no signature");
            return;
        }
        List<String> queries = new ArrayList<String>();
        for (Map.Entry<String, List<String>> e : query.entrySet()) {
            for (String v : e.getValue()) {
                queries.add(e.getKey() + "=" + v);
            }
        }
        String want = Protocol.requestSignature(endpoint, queries, rawBody);
        if (!got.equalsIgnoreCase(want)) {
            // not fatal: the private server trusts its client, this only helps debugging
            Log.i("request signature mismatch on " + endpoint + " (got " + got + " want " + want + ")");
        }
    }

    // ---- small parsing helpers ----

    static String abbreviate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    static String strip(String s, char c) {
        int a = 0;
        int b = s.length();
        while (a < b && s.charAt(a) == c) {
            a++;
        }
        while (b > a && s.charAt(b - 1) == c) {
            b--;
        }
        return s.substring(a, b);
    }

    static String cookie(String header, String name) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            String k = eq < 0 ? p : p.substring(0, eq);
            if (k.equals(name)) {
                return eq < 0 ? "" : p.substring(eq + 1);
            }
        }
        return null;
    }

    /** urllib.parse.parse_qs(keep_blank_values=True). */
    public static Map<String, List<String>> parseQuery(String q) {
        Map<String, List<String>> out = new LinkedHashMap<String, List<String>>();
        if (q.isEmpty()) {
            return out;
        }
        for (String part : q.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String k = percentDecode(eq < 0 ? part : part.substring(0, eq), true);
            String v = eq < 0 ? "" : percentDecode(part.substring(eq + 1), true);
            List<String> l = out.get(k);
            if (l == null) {
                l = new ArrayList<String>();
                out.put(k, l);
            }
            l.add(v);
        }
        return out;
    }

    public static String percentDecode(String s, boolean plusIsSpace) {
        if (s.indexOf('%') < 0 && (!plusIsSpace || s.indexOf('+') < 0)) {
            return s;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) {
                out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2)));
                i += 3;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
                i++;
            } else {
                int cp = s.codePointAt(i);
                byte[] b = new String(Character.toChars(cp)).getBytes(Protocol.UTF8);
                out.write(b, 0, b.length);
                i += Character.charCount(cp);
            }
        }
        return new String(out.toByteArray(), Protocol.UTF8);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}

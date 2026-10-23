package sgm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Everything a handler needs for one request. */
public final class Ctx {
    public final Server app;
    public final Content content;
    public final Store store;
    public final String method;
    public final String endpoint;
    public final Map<String, List<String>> query;
    public final Map<String, Object> body;
    public final Map<String, String> headers;
    public Map<String, Object> user;
    public boolean dirty;
    public String setCookie;
    public final long nowMs = System.currentTimeMillis();

    Ctx(Server app, String method, String endpoint, Map<String, List<String>> query, Map<String, Object> body,
        Map<String, String> headers) {
        this.app = app;
        this.content = app.content;
        this.store = app.store;
        this.method = method;
        this.endpoint = endpoint;
        this.query = query;
        this.body = body;
        this.headers = headers;
    }

    public String q(String key) {
        return q(key, null);
    }

    public String q(String key, String def) {
        List<String> v = query.get(key);
        return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
    }

    /** Query list values: the client sends repeated keys or comma separated values. */
    public List<String> qList(String key) {
        List<String> out = new ArrayList<String>();
        List<String> v = query.get(key);
        if (v != null) {
            for (String s : v) {
                for (String x : s.split(",")) {
                    if (!x.isEmpty()) {
                        out.add(x);
                    }
                }
            }
        }
        return out;
    }

    public boolean qBool(String key, boolean def) {
        String v = q(key);
        if (v == null) {
            return def;
        }
        v = v.toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes");
    }

    public boolean qBool(String key) {
        return qBool(key, false);
    }

    /** Integer query value; {@code int(q or def)} semantics. */
    public int qInt(String key, int def) {
        String v = q(key);
        if (v == null || v.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new Protocol.ApiError(400, "BadRequest", "bad " + key);
        }
    }

    public void save() {
        dirty = true;
    }

    // ---- shortcuts used throughout the handlers ----

    public Map<String, Object> chars() {
        return Json.child(user, "characters");
    }

    public Players players() {
        return app.players;
    }
}

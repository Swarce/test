package sgm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A group of endpoint handlers. Routes are registered with {@link #route} and dispatched to
 * {@link #call} by name (Java 8 source level without lambdas keeps this Android-friendly).
 */
public abstract class Module {
    public static final int NO_AUTH = 1;
    /** the client sets expectedResponseEncoding=Base64 for this request */
    public static final int B64 = 2;
    /** the client checks X-SG-SIG on the response */
    public static final int SIGN = 4;

    protected final Server app;

    protected Module(Server app) {
        this.app = app;
    }

    protected abstract Object call(String name, Ctx ctx, Map<String, String> p) throws Exception;

    /**
     * Registers /v1/&lt;pattern&gt; for one or more methods ("GET|PUT"). In the pattern {name}
     * captures one path segment ([^/]+) and {name:regex} a custom expression (no capturing groups).
     */
    protected void route(String methods, String pattern, String name, int flags) {
        List<String> params = new ArrayList<String>();
        StringBuilder rx = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char ch = pattern.charAt(i);
            if (ch == '{') {
                int end = pattern.indexOf('}', i);
                String spec = pattern.substring(i + 1, end);
                int colon = spec.indexOf(':');
                params.add(colon < 0 ? spec : spec.substring(0, colon));
                rx.append('(').append(colon < 0 ? "[^/]+" : spec.substring(colon + 1)).append(')');
                i = end + 1;
            } else {
                rx.append(ch);
                i++;
            }
        }
        rx.append('$');
        Pattern re = Pattern.compile(rx.toString());
        for (String m : methods.split("\\|")) {
            app.routes.add(new Route(m, re, params, this, name, flags));
        }
    }

    protected void route(String methods, String pattern, String name) {
        route(methods, pattern, name, 0);
    }

    static final class Route {
        final String method;
        final Pattern regex;
        final List<String> params;
        final Module module;
        final String name;
        final int flags;

        Route(String method, Pattern regex, List<String> params, Module module, String name, int flags) {
            this.method = method;
            this.regex = regex;
            this.params = params;
            this.module = module;
            this.name = name;
            this.flags = flags;
        }

        boolean has(int flag) {
            return (flags & flag) != 0;
        }

        Map<String, String> match(String method, String endpoint) {
            if (!this.method.equals(method)) {
                return null;
            }
            Matcher m = regex.matcher(endpoint);
            if (!m.matches()) {
                return null;
            }
            Map<String, String> out = new java.util.HashMap<String, String>();
            for (int g = 0; g < params.size(); g++) {
                out.put(params.get(g), m.group(g + 1));
            }
            return out;
        }
    }

    // ---- helpers shared by handler modules ----

    protected static Protocol.ApiError err(int status, String code, String message) {
        return new Protocol.ApiError(status, code, message);
    }

    protected static Map<String, Object> data(Object d) {
        return Json.obj("data", d);
    }

    protected static Map<String, Object> empty() {
        return Json.obj();
    }
}

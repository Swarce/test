package sgm.core;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON for the server: objects become LinkedHashMap, arrays ArrayList, integers Long,
 * other numbers Double. Works on the desktop JVM and on Android (no org.json dependency).
 */
public final class Json {
    private Json() {
    }

    public static Object parse(String s) {
        return parse(s, new java.util.HashMap<Object, Object>());
    }

    /** Parses with a shared pool: equal keys, short strings and numbers become one instance. */
    public static Object parse(String s, java.util.HashMap<Object, Object> pool) {
        P p = new P(s, pool);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != s.length()) {
            throw new IllegalArgumentException("trailing data at " + p.i);
        }
        return v;
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            str(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number");
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d).append(".0");
            } else {
                sb.append(Double.toString(d));
            }
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            List<Object> l = new ArrayList<Object>();
            for (Object o : (Object[]) v) {
                l.add(o);
            }
            write(sb, l);
        } else {
            str(sb, v.toString());
        }
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static final class P {
        final String s;
        int i;
        /** Keys and short values repeat a lot in game data; sharing them saves most of the heap. */
        final java.util.HashMap<Object, Object> pool;

        String share(String v) {
            return v.length() > 64 ? v : (String) shareAny(v);
        }

        Object shareAny(Object v) {
            Object have = pool.get(v);
            if (have == null) {
                pool.put(v, v);
                return v;
            }
            return have;
        }

        P(String s, java.util.HashMap<Object, Object> pool) {
            this.s = s;
            this.pool = pool;
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return obj();
            }
            if (c == '[') {
                return arr();
            }
            if (c == '"') {
                return share(string());
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            return number();
        }

        Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = share(string());
                ws();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + (i - 1));
                }
            }
        }

        List<Object> arr() {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + (i - 1));
                }
            }
        }

        void expect(char c) {
            if (s.charAt(i) != c) {
                throw new IllegalArgumentException("expected " + c + " at " + i);
            }
            i++;
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = i;
            boolean frac = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '.' || c == 'e' || c == 'E') {
                    frac = true;
                } else if (!(c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    break;
                }
                i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty()) {
                throw new IllegalArgumentException("bad token at " + start);
            }
            if (!frac) {
                try {
                    long v = Long.parseLong(t);
                    return v >= -128 && v <= 127 ? Long.valueOf(v) : shareAny(v);
                } catch (NumberFormatException ignored) {
                    // fall through to double
                }
            }
            return shareAny(Double.parseDouble(t));
        }
    }

    // ---- typed access helpers (the server works on JSON-shaped maps, like the Python version) ----

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int k = 0; k + 1 < kv.length; k += 2) {
            m.put((String) kv[k], kv[k + 1]);
        }
        return m;
    }

    public static List<Object> arr(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object o : items) {
            l.add(o);
        }
        return l;
    }

    public static Map<String, Object> getMap(Map<String, Object> m, String k) {
        return m == null ? null : map(m.get(k));
    }

    public static List<Object> getList(Map<String, Object> m, String k) {
        List<Object> l = m == null ? null : list(m.get(k));
        return l == null ? new ArrayList<Object>() : l;
    }

    /** Child map, created if missing. */
    public static Map<String, Object> child(Map<String, Object> m, String k) {
        Map<String, Object> c = map(m.get(k));
        if (c == null) {
            c = new LinkedHashMap<String, Object>();
            m.put(k, c);
        }
        return c;
    }

    /** Child list, created if missing. */
    public static List<Object> childList(Map<String, Object> m, String k) {
        List<Object> c = list(m.get(k));
        if (c == null) {
            c = new ArrayList<Object>();
            m.put(k, c);
        }
        return c;
    }

    public static String str(Map<String, Object> m, String k) {
        return str(m, k, "");
    }

    public static String str(Map<String, Object> m, String k, String def) {
        Object o = m == null ? null : m.get(k);
        return o == null ? def : o.toString();
    }

    public static long lng(Map<String, Object> m, String k, long def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1 : 0;
        }
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static int i(Map<String, Object> m, String k, int def) {
        return (int) lng(m, k, def);
    }

    public static int i(Map<String, Object> m, String k) {
        return i(m, k, 0);
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).longValue() != 0;
        }
        if (o instanceof String) {
            return "true".equalsIgnoreCase((String) o) || "1".equals(o);
        }
        return false;
    }

    /** m[k] += delta (missing counts as 0); returns the new value. */
    public static long add(Map<String, Object> m, String k, long delta) {
        long v = lng(m, k, 0) + delta;
        m.put(k, v);
        return v;
    }

    /** Like Python's {@code m.get(k) or def}: missing, null, zero, empty and false all give def. */
    public static double orD(Map<String, Object> m, String k, double def) {
        double v = dbl(m, k, 0);
        return v == 0 ? def : v;
    }

    /** True for null, false, 0, "" and empty containers (Python truthiness). */
    public static boolean falsy(Object o) {
        if (o == null) {
            return true;
        }
        if (o instanceof Boolean) {
            return !(Boolean) o;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue() == 0;
        }
        if (o instanceof String) {
            return ((String) o).isEmpty();
        }
        if (o instanceof Map) {
            return ((Map<?, ?>) o).isEmpty();
        }
        if (o instanceof List) {
            return ((List<?>) o).isEmpty();
        }
        return false;
    }

    public static boolean truthy(Map<String, Object> m, String k) {
        return m != null && !falsy(m.get(k));
    }

    /** Deep copy of a JSON value. */
    @SuppressWarnings("unchecked")
    public static Object copy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                out.put(e.getKey(), copy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<Object>();
            for (Iterator<Object> it = ((List<Object>) v).iterator(); it.hasNext(); ) {
                out.add(copy(it.next()));
            }
            return out;
        }
        return v;
    }
}

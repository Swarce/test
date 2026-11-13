package sgmo.helper;

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.value.BooleanEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.ByteEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.CharEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.DoubleEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.FloatEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.LongEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.NullEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.ShortEncodedValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code dex-compare} command: prove two dex files are structurally and semantically
 * equal, except for a given set of excluded methods.
 *
 * <p>This is how the patcher checks that a rewrite touched only what it meant to: the
 * class set, each class's flags/superclass/interfaces/annotations-present, its fields and
 * method signatures, and the {@link Dex#semSha256 semSha256} of every non-excluded method
 * body must all match.
 */
final class Compare {
    private Compare() {}

    /** Run the comparison; returns {@code {ok, differences}}. */
    static Map<String, Object> run(String aPath, String bPath, List<String> excludeKeys) throws Exception {
        DexBackedDexFile a = Dex.load(aPath);
        DexBackedDexFile b = Dex.load(bPath);
        Set<String> exclude = new HashSet<>(excludeKeys);

        List<String> diffs = new ArrayList<>();

        Map<String, DexBackedClassDef> ca = byType(a);
        Map<String, DexBackedClassDef> cb = byType(b);

        // Class set.
        for (String t : new TreeSet<>(ca.keySet())) {
            if (!cb.containsKey(t)) diffs.add("class only in A: " + t);
        }
        for (String t : new TreeSet<>(cb.keySet())) {
            if (!ca.containsKey(t)) diffs.add("class only in B: " + t);
        }

        for (String t : new TreeSet<>(ca.keySet())) {
            ClassDef x = ca.get(t);
            ClassDef y = cb.get(t);
            if (y == null) continue;
            compareClass(x, y, exclude, diffs);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", diffs.isEmpty());
        out.put("differences", diffs);
        return out;
    }

    private static void compareClass(ClassDef x, ClassDef y, Set<String> exclude, List<String> diffs) {
        String t = x.getType();
        if (x.getAccessFlags() != y.getAccessFlags()) {
            diffs.add(t + ": access flags " + x.getAccessFlags() + " != " + y.getAccessFlags());
        }
        if (!eq(x.getSuperclass(), y.getSuperclass())) {
            diffs.add(t + ": superclass " + x.getSuperclass() + " != " + y.getSuperclass());
        }
        if (!new TreeSet<>(x.getInterfaces()).equals(new TreeSet<>(y.getInterfaces()))) {
            diffs.add(t + ": interfaces differ");
        }
        // At least: a class that has annotations must still have them.
        boolean xa = !x.getAnnotations().isEmpty();
        boolean ya = !y.getAnnotations().isEmpty();
        if (xa != ya) {
            diffs.add(t + ": class annotations present " + xa + " != " + ya);
        }

        compareFields(t, "static", x.getStaticFields(), y.getStaticFields(), diffs);
        compareFields(t, "instance", x.getInstanceFields(), y.getInstanceFields(), diffs);
        compareMethods(t, x, y, exclude, diffs);
    }

    private static void compareFields(String t, String kind,
                                      Iterable<? extends Field> xf, Iterable<? extends Field> yf,
                                      List<String> diffs) {
        Map<String, String> mx = fieldMap(xf);
        Map<String, String> my = fieldMap(yf);
        for (String k : new TreeSet<>(mx.keySet())) {
            if (!my.containsKey(k)) {
                diffs.add(t + ": " + kind + " field only in A: " + k);
            } else if (!mx.get(k).equals(my.get(k))) {
                diffs.add(t + ": " + kind + " field " + k + " differs (" + mx.get(k) + " != " + my.get(k) + ")");
            }
        }
        for (String k : new TreeSet<>(my.keySet())) {
            if (!mx.containsKey(k)) diffs.add(t + ": " + kind + " field only in B: " + k);
        }
    }

    private static Map<String, String> fieldMap(Iterable<? extends Field> fields) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : fields) {
            // A static field with no explicit initial value, and one whose initial value is the
            // type's default (false/0/0.0/null), are the same thing: dexlib2's DexPool drops
            // trailing default values when it writes a dex, so the original (DexBacked) may keep
            // an explicit default that the rewritten copy omits. Normalise both to "default" so
            // that is not reported as a difference.
            EncodedValue iv = f.getInitialValue();
            String init = isDefaultInitialValue(iv) ? "default" : iv.toString();
            m.put(f.getName() + ":" + f.getType(),
                  "flags=" + f.getAccessFlags() + " init=" + init);
        }
        return m;
    }

    /** True for a missing initial value or one equal to its type's default (0/false/0.0/null). */
    private static boolean isDefaultInitialValue(EncodedValue iv) {
        if (iv == null) return true;
        if (iv instanceof BooleanEncodedValue) return !((BooleanEncodedValue) iv).getValue();
        if (iv instanceof ByteEncodedValue) return ((ByteEncodedValue) iv).getValue() == 0;
        if (iv instanceof ShortEncodedValue) return ((ShortEncodedValue) iv).getValue() == 0;
        if (iv instanceof CharEncodedValue) return ((CharEncodedValue) iv).getValue() == 0;
        if (iv instanceof IntEncodedValue) return ((IntEncodedValue) iv).getValue() == 0;
        if (iv instanceof LongEncodedValue) return ((LongEncodedValue) iv).getValue() == 0L;
        if (iv instanceof FloatEncodedValue) return ((FloatEncodedValue) iv).getValue() == 0.0f;
        if (iv instanceof DoubleEncodedValue) return ((DoubleEncodedValue) iv).getValue() == 0.0;
        if (iv instanceof NullEncodedValue) return true;
        return false;
    }

    private static void compareMethods(String t, ClassDef x, ClassDef y,
                                       Set<String> exclude, List<String> diffs) {
        Map<String, Method> mx = methodMap(x);
        Map<String, Method> my = methodMap(y);
        for (String k : new TreeSet<>(mx.keySet())) {
            Method a = mx.get(k);
            Method b = my.get(k);
            if (b == null) {
                diffs.add(t + ": method only in A: " + k);
                continue;
            }
            if (a.getAccessFlags() != b.getAccessFlags()) {
                diffs.add(t + "->" + k + ": flags " + a.getAccessFlags() + " != " + b.getAccessFlags());
            }
            boolean ha = a.getImplementation() != null;
            boolean hb = b.getImplementation() != null;
            if (ha != hb) {
                diffs.add(t + "->" + k + ": has-code " + ha + " != " + hb);
                continue;
            }
            String excludeKey = t + "->" + k;
            if (exclude.contains(excludeKey)) continue;
            String sa = Dex.semSha256(a);
            String sb = Dex.semSha256(b);
            if (!eq(sa, sb)) {
                diffs.add(t + "->" + k + ": body semSha256 differs");
            }
        }
        for (String k : new TreeSet<>(my.keySet())) {
            if (!mx.containsKey(k)) diffs.add(t + ": method only in B: " + k);
        }
    }

    private static Map<String, Method> methodMap(ClassDef cls) {
        Map<String, Method> m = new LinkedHashMap<>();
        for (Method me : cls.getMethods()) {
            m.put(me.getName() + Patch.descriptorOf(me), me);
        }
        return m;
    }

    private static Map<String, DexBackedClassDef> byType(DexBackedDexFile dex) {
        Map<String, DexBackedClassDef> m = new LinkedHashMap<>();
        for (DexBackedClassDef c : dex.getClasses()) {
            m.put(c.getType(), c);
        }
        return m;
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}

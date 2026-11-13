package sgmo.helper;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code dex-edit} command: apply several method edits — stubs and leading-call injections — to
 * one dex in a <b>single</b> rebuild.
 *
 * <p>This is how composed patches stay fingerprintable: each edit's raw fingerprint is checked
 * against the <i>input</i> dex before anything is written, so every site is pinned to the verified
 * build. (Applying edits as separate rebuilds would renumber the constant pool and shift the raw
 * bytes — hence the fingerprints — of still-unedited methods.)
 */
final class DexEdit {
    private DexEdit() {}

    private static final Pattern INVOKE = Pattern.compile("^(L[^;]+;)->([^(]+)\\(\\)V$");

    /** One edit: a stub, or a leading-call injection. */
    static final class Op {
        String kind;            // "stub" | "inject"
        String cls, name, desc;
        String expectRawSha256;
        Patch.Stub stub;        // for kind=stub
        String superCls;        // for SUPER_* stubs
        String invoke;          // for kind=inject: "Lowner;->m()V"
        boolean required;
    }

    static Map<String, Object> run(String inPath, String outPath, List<Op> ops) throws Exception {
        DexBackedDexFile dex = Dex.load(inPath);

        // Locate every target and verify fingerprints against the input (nothing written yet).
        Map<String, Method> found = new LinkedHashMap<>();
        Map<String, DexBackedClassDef> owner = new LinkedHashMap<>();
        Map<String, Op> byKey = new LinkedHashMap<>();
        for (Op o : ops) {
            if (o.expectRawSha256 == null || o.expectRawSha256.isBlank()) {
                throw new IllegalArgumentException("edit " + key(o) + " has no expectRawSha256");
            }
            byKey.put(key(o), o);
        }
        for (DexBackedClassDef c : dex.getClasses()) {
            for (Method m : c.getMethods()) {
                String k = m.getDefiningClass() + "->" + m.getName() + Patch.descriptorOf(m);
                if (byKey.containsKey(k)) {
                    found.put(k, m);
                    owner.put(k, c);
                }
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        boolean anyRequiredFailed = false;
        for (Op o : ops) {
            String k = key(o);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("site", k);
            r.put("kind", o.kind);
            Method m = found.get(k);
            if (m == null) {
                r.put("status", "missing");
                if (o.required) anyRequiredFailed = true;
                results.add(r);
                continue;
            }
            String actual = Dex.rawSha256(m);
            r.put("actualRawSha256", actual);
            if (!o.expectRawSha256.equalsIgnoreCase(actual)) {
                r.put("status", "mismatch");
                if (o.required) anyRequiredFailed = true;
                results.add(r);
                continue;
            }
            // Validate the edit can be applied (spec errors throw).
            if ("stub".equals(o.kind)) {
                Patch.Site site = new Patch.Site();
                site.cls = o.cls; site.name = o.name; site.desc = o.desc; site.stub = o.stub; site.superCls = o.superCls;
                String problem = Patch.stubFitProblem(site, m, owner.get(k));
                if (problem != null) throw new IllegalArgumentException("site " + k + ": " + problem);
            } else if ("inject".equals(o.kind)) {
                if (m.getImplementation() == null) throw new IllegalArgumentException("cannot inject into abstract/native " + k);
                if (!m.getImplementation().getTryBlocks().isEmpty())
                    throw new IllegalArgumentException("cannot inject before try blocks: " + k);
            } else {
                throw new IllegalArgumentException("unknown edit kind: " + o.kind);
            }
            r.put("status", "edited");
            results.add(r);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (anyRequiredFailed) {
            out.put("ok", false);
            out.put("sites", results);
            out.put("out", null);
            return out;
        }

        // One rebuild: only classes holding an edited method change, and within them only that method.
        Map<String, Map<String, Op>> byClass = new LinkedHashMap<>();
        for (Op o : ops) {
            if ("edited".equals(statusOf(results, key(o)))) {
                byClass.computeIfAbsent(o.cls, x -> new LinkedHashMap<>()).put(o.name + o.desc, o);
            }
        }
        DexPool pool = new DexPool(dex.getOpcodes());
        for (DexBackedClassDef c : dex.getClasses()) {
            Map<String, Op> edits = byClass.get(c.getType());
            if (edits == null) {
                pool.internClass(c);
            } else {
                pool.internClass(rebuild(c, edits));
            }
        }
        FileDataStore store = new FileDataStore(new File(outPath));
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        out.put("ok", true);
        out.put("sites", results);
        out.put("out", outPath);
        return out;
    }

    private static String statusOf(List<Map<String, Object>> results, String key) {
        for (Map<String, Object> r : results) {
            if (key.equals(r.get("site"))) return (String) r.get("status");
        }
        return null;
    }

    private static ClassDef rebuild(DexBackedClassDef c, Map<String, Op> edits) {
        List<Method> methods = new ArrayList<>();
        for (Method m : c.getMethods()) {
            Op o = edits.get(m.getName() + Patch.descriptorOf(m));
            if (o == null) {
                methods.add(m);
            } else if ("stub".equals(o.kind)) {
                methods.add(stub(m, o));
            } else {
                methods.add(inject(m, o));
            }
        }
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                c.getSourceFile(), c.getAnnotations(), c.getFields(), methods);
    }

    private static ImmutableMethod stub(Method m, Op o) {
        boolean isStatic = (m.getAccessFlags() & com.android.tools.smali.dexlib2.AccessFlags.STATIC.getValue()) != 0;
        int ins = Dex.insRegisterCount(m);
        Patch.Site site = new Patch.Site();
        site.cls = o.cls; site.name = o.name; site.desc = o.desc; site.stub = o.stub; site.superCls = o.superCls;
        ImmutableMethodImplementation impl = Patch.buildStub(site, isStatic, ins, m.getName(), o.desc);
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), impl);
    }

    private static ImmutableMethod inject(Method m, Op o) {
        Matcher mm = INVOKE.matcher(o.invoke == null ? "" : o.invoke);
        if (!mm.matches()) throw new IllegalArgumentException("invoke must be 'Lowner;->name()V': " + o.invoke);
        ImmutableMethodReference callee = new ImmutableMethodReference(mm.group(1), mm.group(2), new ArrayList<>(), "V");
        MethodImplementation impl = m.getImplementation();
        List<ImmutableInstruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, callee));
        for (Instruction ins : impl.getInstructions()) code.add(ImmutableInstruction.of(ins));
        ImmutableMethodImplementation ni = new ImmutableMethodImplementation(impl.getRegisterCount(), code, null, null);
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), ni);
    }

    private static String key(Op o) {
        return o.cls + "->" + o.name + o.desc;
    }
}

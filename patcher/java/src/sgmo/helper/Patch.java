package sgmo.helper;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code dex-patch} command: replace the implementation of named methods with a
 * fixed, safe stub, after checking each site's raw fingerprint against the verified build.
 *
 * <p>Nothing half-patches. If a required site's fingerprint does not match (or the method
 * is missing), no output dex is written and the process exits with a non-zero code.
 */
final class Patch {
    private Patch() {}

    /** The closed set of stub bodies the patcher may emit. */
    enum Stub { RETURN_VOID, RETURN_NULL, RETURN_TRUE, RETURN_FALSE, SUPER_CALL, SUPER_CALL_THEN_FINISH }

    /** One site to patch, as parsed from the spec. */
    static final class Site {
        String cls;
        String name;
        String desc;
        String expectRawSha256;
        Stub stub;
        String superCls;       // required for SUPER_* stubs
        boolean required;
    }

    /** The outcome of one site after a patch attempt. */
    static final class SiteResult {
        final Site site;
        String status;         // patched | mismatch | missing
        String actualRawSha256;

        SiteResult(Site site) { this.site = site; }
    }

    /**
     * Run the patch. Returns a result map ready to be serialized; writes {@code out} only
     * when no required site failed.
     *
     * @throws RequiredSiteFailure if a required site mismatched or was missing (exit code 2)
     */
    static Map<String, Object> run(String inPath, String outPath, List<Site> sites) throws Exception {
        DexBackedDexFile dex = Dex.load(inPath);

        // Index the sites by (class, name, desc) for a single pass over the dex.
        Map<String, Site> byKey = new LinkedHashMap<>();
        Map<String, SiteResult> results = new LinkedHashMap<>();
        for (Site s : sites) {
            String key = key(s.cls, s.name, s.desc);
            byKey.put(key, s);
            results.put(key, new SiteResult(s));
        }

        // Every site must carry the verified-build fingerprint: the patcher never rewrites a
        // method it has not pinned. A missing fingerprint is a spec error, not a site outcome.
        for (Site s : sites) {
            if (s.expectRawSha256 == null || s.expectRawSha256.isBlank()) {
                throw new IllegalArgumentException(
                        "site " + key(s.cls, s.name, s.desc) + " has no expectRawSha256");
            }
        }

        // Find each method (with its owning class), compare the raw fingerprint, decide its fate.
        Map<String, Method> found = new LinkedHashMap<>();
        Map<String, DexBackedClassDef> ownerOf = new LinkedHashMap<>();
        for (DexBackedClassDef cls : dex.getClasses()) {
            for (Method m : cls.getMethods()) {
                String key = key(m.getDefiningClass(), m.getName(), descriptorOf(m));
                if (byKey.containsKey(key)) {
                    found.put(key, m);
                    ownerOf.put(key, cls);
                }
            }
        }

        boolean anyRequiredFailed = false;
        for (Map.Entry<String, Site> e : byKey.entrySet()) {
            String key = e.getKey();
            Site site = e.getValue();
            SiteResult r = results.get(key);
            Method m = found.get(key);
            if (m == null) {
                r.status = "missing";
                r.actualRawSha256 = null;
                if (site.required) anyRequiredFailed = true;
                continue;
            }
            // The stub must suit the method, independent of whether the fingerprint matches.
            // An unsuitable stub would write a dex that fails ART verification at runtime, so
            // reject it up front as a spec error.
            String problem = stubFitProblem(site, m, ownerOf.get(key));
            if (problem != null) {
                throw new IllegalArgumentException("site " + key + ": " + problem);
            }
            String actual = Dex.rawSha256(m);
            r.actualRawSha256 = actual;
            if (site.expectRawSha256 != null && !site.expectRawSha256.equalsIgnoreCase(actual)) {
                r.status = "mismatch";
                if (site.required) anyRequiredFailed = true;
            } else {
                r.status = "patched";
            }
        }

        if (anyRequiredFailed) {
            throw new RequiredSiteFailure(orderedResults(sites, results));
        }

        // Build the output dex, replacing only the matched & matching methods.
        rebuildAndWrite(dex, outPath, byKey, results);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sites", orderedResults(sites, results));
        out.put("out", outPath);
        return out;
    }

    private static void rebuildAndWrite(DexBackedDexFile dex, String outPath,
                                        Map<String, Site> byKey,
                                        Map<String, SiteResult> results) throws Exception {
        // Which classes hold a patched method?
        Map<String, Map<String, Site>> patchesByClass = new LinkedHashMap<>();
        for (Map.Entry<String, Site> e : byKey.entrySet()) {
            SiteResult r = results.get(e.getKey());
            if (!"patched".equals(r.status)) continue;
            Site s = e.getValue();
            patchesByClass.computeIfAbsent(s.cls, k -> new LinkedHashMap<>())
                          .put(s.name + s.desc, s);
        }

        DexPool pool = new DexPool(dex.getOpcodes());
        for (DexBackedClassDef cls : dex.getClasses()) {
            Map<String, Site> classPatches = patchesByClass.get(cls.getType());
            if (classPatches == null) {
                pool.internClass(cls);
                continue;
            }
            pool.internClass(rebuildClass(cls, classPatches));
        }
        com.android.tools.smali.dexlib2.writer.io.FileDataStore store =
                new com.android.tools.smali.dexlib2.writer.io.FileDataStore(new java.io.File(outPath));
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
    }

    /** Rebuild a class with the named methods replaced by their stub bodies. */
    private static ClassDef rebuildClass(DexBackedClassDef cls, Map<String, Site> classPatches) {
        List<Method> methods = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            Site s = classPatches.get(m.getName() + descriptorOf(m));
            if (s != null) {
                methods.add(stubMethod(m, s));
            } else {
                methods.add(m);
            }
        }
        return new ImmutableClassDef(
                cls.getType(),
                cls.getAccessFlags(),
                cls.getSuperclass(),
                cls.getInterfaces(),
                cls.getSourceFile(),
                cls.getAnnotations(),
                cls.getFields(),
                methods);
    }

    /** Build an ImmutableMethod identical to {@code m} but with a stub implementation. */
    private static ImmutableMethod stubMethod(Method m, Site site) {
        boolean isStatic = (m.getAccessFlags() & AccessFlags.STATIC.getValue()) != 0;
        int ins = Dex.insRegisterCount(m);
        ImmutableMethodImplementation impl = buildStub(site, isStatic, ins, m.getName(), site.desc);
        return new ImmutableMethod(
                m.getDefiningClass(),
                m.getName(),
                m.getParameters(),
                m.getReturnType(),
                m.getAccessFlags(),
                m.getAnnotations(),
                m.getHiddenApiRestrictions(),
                impl);
    }

    /** Assemble a stub body (no try blocks, no debug info). */
    static ImmutableMethodImplementation buildStub(Site site, boolean isStatic, int ins,
                                                   String name, String desc) {
        List<ImmutableInstruction> code = new ArrayList<>();
        switch (site.stub) {
            case RETURN_VOID: {
                code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
                return new ImmutableMethodImplementation(ins, code, null, null);
            }
            case RETURN_NULL: {
                // One extra local register (v0); the parameters occupy the top `ins` registers.
                int regs = ins + 1;
                code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));
                code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
                return new ImmutableMethodImplementation(regs, code, null, null);
            }
            case RETURN_TRUE:
            case RETURN_FALSE: {
                // const/4 v0, 1|0 ; return v0  (v0 overwrites a param register; we use none)
                int regs = Math.max(ins, 1);
                code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, site.stub == Stub.RETURN_TRUE ? 1 : 0));
                code.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
                return new ImmutableMethodImplementation(regs, code, null, null);
            }
            case SUPER_CALL:
            case SUPER_CALL_THEN_FINISH: {
                if (isStatic) {
                    throw new IllegalArgumentException(
                            "SUPER_* stub is invalid for static method " + name + desc);
                }
                if (site.superCls == null) {
                    throw new IllegalArgumentException("superCls required for SUPER_* stub");
                }
                Dex.ParsedDescriptor pd = Dex.parseDescriptor(desc);
                // Parameters occupy registers 0..ins-1 (registerCount == ins).
                ImmutableMethodReference superRef = new ImmutableMethodReference(
                        site.superCls, name, pd.params, pd.returnType);
                code.add(new ImmutableInstruction3rc(
                        Opcode.INVOKE_SUPER_RANGE, 0, ins, superRef));
                if (site.stub == Stub.SUPER_CALL_THEN_FINISH) {
                    ImmutableMethodReference finishRef = new ImmutableMethodReference(
                            "Landroid/app/Activity;", "finish",
                            new ArrayList<String>(), "V");
                    // invoke-virtual {p0}
                    code.add(new ImmutableInstruction35c(
                            Opcode.INVOKE_VIRTUAL, 1, 0, 0, 0, 0, 0, finishRef));
                }
                code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
                return new ImmutableMethodImplementation(ins, code, null, null);
            }
            default:
                throw new IllegalArgumentException("unknown stub " + site.stub);
        }
    }

    /**
     * Check that a stub can legally replace this method. Returns a human-readable problem, or
     * null if the stub fits. Guards against stubs that would produce a dex ART rejects.
     */
    static String stubFitProblem(Site site, Method m, ClassDef owner) {
        int flags = m.getAccessFlags();
        if ((flags & AccessFlags.NATIVE.getValue()) != 0) return "method is native (no body to replace)";
        if ((flags & AccessFlags.ABSTRACT.getValue()) != 0) return "method is abstract (no body to replace)";
        boolean isStatic = (flags & AccessFlags.STATIC.getValue()) != 0;
        String ret = m.getReturnType();
        switch (site.stub) {
            case RETURN_VOID:
                if (!"V".equals(ret)) return "RETURN_VOID stub on non-void method (returns " + ret + ")";
                return null;
            case RETURN_NULL:
                if (!(ret.startsWith("L") || ret.startsWith("["))) {
                    return "RETURN_NULL stub on non-reference return type " + ret;
                }
                return null;
            case RETURN_TRUE:
            case RETURN_FALSE:
                if (!("Z".equals(ret) || "I".equals(ret) || "B".equals(ret) || "S".equals(ret) || "C".equals(ret))) {
                    return "RETURN_TRUE/FALSE stub on non-int/boolean return type " + ret;
                }
                return null;
            case SUPER_CALL:
            case SUPER_CALL_THEN_FINISH:
                if (isStatic) return "SUPER_* stub on a static method";
                if (!"V".equals(ret)) return "SUPER_* stub on non-void method (returns " + ret + ")";
                if (site.superCls == null || site.superCls.isBlank()) return "SUPER_* stub needs superCls";
                if (owner != null && owner.getSuperclass() != null
                        && !site.superCls.equals(owner.getSuperclass())) {
                    return "superCls " + site.superCls + " != actual superclass " + owner.getSuperclass();
                }
                return null;
            default:
                return "unknown stub " + site.stub;
        }
    }

    private static List<Map<String, Object>> orderedResults(List<Site> sites,
                                                            Map<String, SiteResult> results) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Site s : sites) {
            SiteResult r = results.get(key(s.cls, s.name, s.desc));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cls", s.cls);
            m.put("name", s.name);
            m.put("desc", s.desc);
            m.put("stub", s.stub.name());
            m.put("required", s.required);
            m.put("status", r.status);
            m.put("actualRawSha256", r.actualRawSha256);
            out.add(m);
        }
        return out;
    }

    static String key(String cls, String name, String desc) {
        return cls + "->" + name + desc;
    }

    static String descriptorOf(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) {
            sb.append(p);
        }
        sb.append(')').append(m.getReturnType());
        return sb.toString();
    }

    /** Thrown when a required site mismatched or was missing; carries the per-site results. */
    static final class RequiredSiteFailure extends Exception {
        final List<Map<String, Object>> siteResults;

        RequiredSiteFailure(List<Map<String, Object>> siteResults) {
            this.siteResults = siteResults;
        }
    }
}

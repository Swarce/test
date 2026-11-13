package sgmo.helper;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The {@code selftest} command: build a synthetic dex, patch it with every stub kind,
 * check the results with {@code dex-compare} and by reading the new instructions back, and
 * check the mismatch / required behaviour. Exits 0 only when everything holds.
 */
final class SelfTest {
    private SelfTest() {}

    /** Run the whole self-test; throws on the first failure. */
    static void run(PrintStream log) throws Exception {
        File dir = Files.createTempDirectory("sgmo-selftest").toFile();
        File inDex = new File(dir, "in.dex");
        File outDex = new File(dir, "out.dex");
        File badOut = new File(dir, "bad.dex");
        try {
            byte[] bytes = Synth.build();
            Files.write(inDex.toPath(), bytes);
            log.println("built synthetic dex: " + bytes.length + " bytes");

            // Fingerprint the four target methods from the input.
            DexBackedDexFile dex = Dex.load(inDex.getAbsolutePath());
            String onCreateRaw = rawOf(dex, Synth.ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V");
            String onDestroyRaw = rawOf(dex, Synth.ACTIVITY, "onDestroy", "()V");
            String getThingRaw = rawOf(dex, Synth.ACTIVITY, "getThing", "()Ljava/lang/Object;");
            String onReceiveRaw = rawOf(dex, Synth.RECEIVER, "onReceive",
                    "(Landroid/content/Context;Landroid/content/Intent;)V");
            require(onCreateRaw != null && onReceiveRaw != null, "target methods must have code");

            // Patch each target with a different stub kind.
            List<Patch.Site> sites = new ArrayList<>();
            sites.add(site(Synth.ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V",
                    onCreateRaw, Patch.Stub.SUPER_CALL, "Landroid/app/Activity;", true));
            sites.add(site(Synth.ACTIVITY, "onDestroy", "()V",
                    onDestroyRaw, Patch.Stub.SUPER_CALL_THEN_FINISH, "Landroid/app/Activity;", true));
            sites.add(site(Synth.ACTIVITY, "getThing", "()Ljava/lang/Object;",
                    getThingRaw, Patch.Stub.RETURN_NULL, null, true));
            sites.add(site(Synth.RECEIVER, "onReceive",
                    "(Landroid/content/Context;Landroid/content/Intent;)V",
                    onReceiveRaw, Patch.Stub.RETURN_VOID, null, true));

            Map<String, Object> patchResult =
                    Patch.run(inDex.getAbsolutePath(), outDex.getAbsolutePath(), sites);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> siteResults = (List<Map<String, Object>>) patchResult.get("sites");
            for (Map<String, Object> sr : siteResults) {
                require("patched".equals(sr.get("status")),
                        "site not patched: " + sr.get("name") + " -> " + sr.get("status"));
            }
            log.println("patched " + siteResults.size() + " sites");

            // dex-compare, excluding the patched methods -> ok.
            List<String> exclude = new ArrayList<>();
            exclude.add(Synth.ACTIVITY + "->onCreate(Landroid/os/Bundle;)V");
            exclude.add(Synth.ACTIVITY + "->onDestroy()V");
            exclude.add(Synth.ACTIVITY + "->getThing()Ljava/lang/Object;");
            exclude.add(Synth.RECEIVER
                    + "->onReceive(Landroid/content/Context;Landroid/content/Intent;)V");
            Map<String, Object> cmp =
                    Compare.run(inDex.getAbsolutePath(), outDex.getAbsolutePath(), exclude);
            require(Boolean.TRUE.equals(cmp.get("ok")),
                    "dex-compare should be ok, got: " + cmp.get("differences"));
            log.println("dex-compare ok (excluding the patched methods)");

            // A compare WITHOUT excluding them must report the differences.
            Map<String, Object> cmp2 =
                    Compare.run(inDex.getAbsolutePath(), outDex.getAbsolutePath(),
                            Collections.<String>emptyList());
            require(Boolean.FALSE.equals(cmp2.get("ok")),
                    "dex-compare without excludes should differ");

            // Read the stub bodies back and check their shapes.
            DexBackedDexFile patched = Dex.load(outDex.getAbsolutePath());
            checkReturnVoid(patched, Synth.RECEIVER, "onReceive",
                    "(Landroid/content/Context;Landroid/content/Intent;)V", 3);
            checkReturnNull(patched, Synth.ACTIVITY, "getThing", "()Ljava/lang/Object;");
            checkSuperCall(patched, Synth.ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V", false);
            checkSuperCall(patched, Synth.ACTIVITY, "onDestroy", "()V", true);
            log.println("stub bodies verified by reading instructions back");

            // Mismatch + required: no out written, RequiredSiteFailure raised.
            List<Patch.Site> bad = new ArrayList<>();
            bad.add(site(Synth.ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V",
                    "00".repeat(32), Patch.Stub.SUPER_CALL, "Landroid/app/Activity;", true));
            boolean threw = false;
            try {
                Patch.run(inDex.getAbsolutePath(), badOut.getAbsolutePath(), bad);
            } catch (Patch.RequiredSiteFailure e) {
                threw = true;
            }
            require(threw, "required mismatch must raise RequiredSiteFailure");
            require(!badOut.exists(), "required mismatch must not write out.dex");
            log.println("required-mismatch behaviour ok (no output written)");

            // Optional mismatch: out IS written, status mismatch.
            File optOut = new File(dir, "opt.dex");
            List<Patch.Site> opt = new ArrayList<>();
            opt.add(site(Synth.ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V",
                    "00".repeat(32), Patch.Stub.SUPER_CALL, "Landroid/app/Activity;", false));
            Map<String, Object> optResult =
                    Patch.run(inDex.getAbsolutePath(), optOut.getAbsolutePath(), opt);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> optSites = (List<Map<String, Object>>) optResult.get("sites");
            require("mismatch".equals(optSites.get(0).get("status")),
                    "optional mismatch should be 'mismatch'");
            require(optOut.exists(), "optional mismatch should still write out.dex");

            // Missing method -> status missing.
            File misOut = new File(dir, "mis.dex");
            List<Patch.Site> mis = new ArrayList<>();
            mis.add(site(Synth.ACTIVITY, "noSuchMethod", "()V",
                    null, Patch.Stub.RETURN_VOID, null, false));
            Map<String, Object> misResult =
                    Patch.run(inDex.getAbsolutePath(), misOut.getAbsolutePath(), mis);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> misSites = (List<Map<String, Object>>) misResult.get("sites");
            require("missing".equals(misSites.get(0).get("status")),
                    "unknown method should be 'missing'");

            log.println("selftest OK");
        } finally {
            deleteTree(dir);
        }
    }

    private static void checkReturnVoid(DexBackedDexFile dex, String cls, String name,
                                        String desc, int expectRegs) {
        MethodImplementation impl = implOf(dex, cls, name, desc);
        require(impl.getRegisterCount() == expectRegs,
                name + ": registers " + impl.getRegisterCount() + " != " + expectRegs);
        List<Instruction> ins = toList(impl);
        require(ins.size() == 1 && ins.get(0).getOpcode() == Opcode.RETURN_VOID,
                name + ": expected a single return-void");
    }

    private static void checkReturnNull(DexBackedDexFile dex, String cls, String name, String desc) {
        MethodImplementation impl = implOf(dex, cls, name, desc);
        List<Instruction> ins = toList(impl);
        require(ins.size() == 2
                        && ins.get(0).getOpcode() == Opcode.CONST_4
                        && ins.get(1).getOpcode() == Opcode.RETURN_OBJECT,
                name + ": expected const/4 + return-object");
        // ins count for getThing()Object is 1 (this); stub adds one local register.
        require(impl.getRegisterCount() == 2,
                name + ": registers " + impl.getRegisterCount() + " != 2");
    }

    private static void checkSuperCall(DexBackedDexFile dex, String cls, String name,
                                       String desc, boolean thenFinish) {
        MethodImplementation impl = implOf(dex, cls, name, desc);
        List<Instruction> ins = toList(impl);
        require(ins.get(0).getOpcode() == Opcode.INVOKE_SUPER_RANGE,
                name + ": expected invoke-super/range first");
        String superRef = Dex.renderReference(((ReferenceInstruction) ins.get(0)).getReference());
        require(superRef.contains("Landroid/app/Activity;->" + name),
                name + ": super call must target Activity." + name + ", got " + superRef);
        if (thenFinish) {
            require(ins.get(1).getOpcode() == Opcode.INVOKE_VIRTUAL,
                    name + ": expected invoke-virtual finish");
            String fin = Dex.renderReference(((ReferenceInstruction) ins.get(1)).getReference());
            require(fin.contains("->finish("), name + ": second call must be finish(), got " + fin);
            require(ins.get(2).getOpcode() == Opcode.RETURN_VOID, name + ": expected return-void last");
        } else {
            require(ins.get(1).getOpcode() == Opcode.RETURN_VOID, name + ": expected return-void last");
        }
    }

    // ---- small helpers -----------------------------------------------------

    private static Patch.Site site(String cls, String name, String desc, String expectRaw,
                                   Patch.Stub stub, String superCls, boolean required) {
        Patch.Site s = new Patch.Site();
        s.cls = cls;
        s.name = name;
        s.desc = desc;
        s.expectRawSha256 = expectRaw;
        s.stub = stub;
        s.superCls = superCls;
        s.required = required;
        return s;
    }

    private static String rawOf(DexBackedDexFile dex, String cls, String name, String desc) {
        Method m = findMethod(dex, cls, name, desc);
        return m == null ? null : Dex.rawSha256(m);
    }

    private static MethodImplementation implOf(DexBackedDexFile dex, String cls, String name, String desc) {
        Method m = findMethod(dex, cls, name, desc);
        require(m != null, "method not found: " + cls + "->" + name + desc);
        MethodImplementation impl = m.getImplementation();
        require(impl != null, "method has no code: " + name);
        return impl;
    }

    private static Method findMethod(DexBackedDexFile dex, String cls, String name, String desc) {
        for (DexBackedClassDef c : dex.getClasses()) {
            if (!c.getType().equals(cls)) continue;
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && Patch.descriptorOf(m).equals(desc)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static List<Instruction> toList(MethodImplementation impl) {
        List<Instruction> list = new ArrayList<>();
        for (Instruction i : impl.getInstructions()) list.add(i);
        return list;
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}

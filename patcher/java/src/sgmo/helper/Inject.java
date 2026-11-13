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
 * The {@code dex-inject-call} command: prepend a single {@code invoke-static {}} (a zero-argument
 * static call) to the body of one named, fingerprinted method, leaving everything else in the dex
 * unchanged. Used to call {@code Lsgm/offline/Boot;->start()V} at the top of the app's
 * {@code Application.attachBaseContext}.
 *
 * <p>A zero-argument invoke needs no registers, so the method's register count is untouched.
 */
final class Inject {
    private Inject() {}

    static final class Site {
        String cls;
        String name;
        String desc;
        String expectRawSha256;
        String invoke; // "Lowner;->method()V" — must be a void, zero-arg static
        boolean required;
    }

    private static final Pattern INVOKE = Pattern.compile("^(L[^;]+;)->([^(]+)\\(\\)V$");

    static Map<String, Object> run(String inPath, String outPath, Site site) throws Exception {
        if (site.expectRawSha256 == null || site.expectRawSha256.isBlank()) {
            throw new IllegalArgumentException("inject site has no expectRawSha256");
        }
        Matcher m = INVOKE.matcher(site.invoke == null ? "" : site.invoke);
        if (!m.matches()) {
            throw new IllegalArgumentException("invoke must be 'Lowner;->name()V', got: " + site.invoke);
        }
        ImmutableMethodReference callee =
                new ImmutableMethodReference(m.group(1), m.group(2), new ArrayList<>(), "V");

        DexBackedDexFile dex = Dex.load(inPath);
        String key = site.cls + "->" + site.name + site.desc;

        Method target = null;
        DexBackedClassDef targetClass = null;
        for (DexBackedClassDef c : dex.getClasses()) {
            if (!c.getType().equals(site.cls)) {
                continue;
            }
            for (Method me : c.getMethods()) {
                if (me.getName().equals(site.name) && Patch.descriptorOf(me).equals(site.desc)) {
                    target = me;
                    targetClass = c;
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", key);
        if (target == null) {
            out.put("status", "missing");
            out.put("actualRawSha256", null);
            return out;
        }
        String actual = Dex.rawSha256(target);
        out.put("actualRawSha256", actual);
        if (!site.expectRawSha256.equalsIgnoreCase(actual)) {
            out.put("status", "mismatch");
            return out;
        }
        if (target.getImplementation() == null) {
            throw new IllegalArgumentException("cannot inject into an abstract/native method: " + key);
        }

        // Rebuild the dex: only the target class changes, and within it only the target method
        // gains a leading invoke-static.
        DexPool pool = new DexPool(dex.getOpcodes());
        for (DexBackedClassDef c : dex.getClasses()) {
            if (c.getType().equals(site.cls)) {
                pool.internClass(rebuildClass(c, site, callee));
            } else {
                pool.internClass(c);
            }
        }
        FileDataStore store = new FileDataStore(new File(outPath));
        try {
            pool.writeTo(store);
        } finally {
            store.close();
        }
        out.put("status", "injected");
        out.put("out", outPath);
        return out;
    }

    private static ClassDef rebuildClass(DexBackedClassDef cls, Site site, ImmutableMethodReference callee) {
        List<Method> methods = new ArrayList<>();
        for (Method me : cls.getMethods()) {
            if (me.getName().equals(site.name) && Patch.descriptorOf(me).equals(site.desc)) {
                methods.add(withLeadingCall(me, callee));
            } else {
                methods.add(me);
            }
        }
        return new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(),
                cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods);
    }

    private static ImmutableMethod withLeadingCall(Method m, ImmutableMethodReference callee) {
        MethodImplementation impl = m.getImplementation();
        int registers = impl.getRegisterCount();
        List<ImmutableInstruction> code = new ArrayList<>();
        // invoke-static {} callee  (zero registers, so register count is unaffected)
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, callee));
        for (Instruction ins : impl.getInstructions()) {
            code.add(ImmutableInstruction.of(ins));
        }
        // Drop debug info and try blocks: prepending an instruction shifts all code offsets, which
        // would invalidate them. The app does not rely on this method's debug info, and
        // attachBaseContext has no try/catch; refuse if it does, rather than corrupt the handlers.
        if (!impl.getTryBlocks().isEmpty()) {
            throw new IllegalArgumentException("cannot inject before a method with try blocks: "
                    + m.getDefiningClass() + "->" + m.getName());
        }
        ImmutableMethodImplementation ni = new ImmutableMethodImplementation(registers, code, null, null);
        return new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(),
                m.getReturnType(), m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), ni);
    }
}

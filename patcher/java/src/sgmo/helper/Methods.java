package sgmo.helper;

import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code dex-methods} command: emit one JSON object per line for every method that
 * has code, carrying both the raw and the semantic fingerprint.
 */
final class Methods {
    private Methods() {}

    /** Print one JSON line per coded method to {@code out}. */
    static void run(String inPath, PrintStream out) throws Exception {
        DexBackedDexFile dex = Dex.load(inPath);
        for (DexBackedClassDef cls : dex.getClasses()) {
            for (Method m : cls.getMethods()) {
                MethodImplementation impl = m.getImplementation();
                if (impl == null) {
                    continue; // only methods WITH code
                }
                out.println(Json.write(describe(m, impl)));
            }
        }
    }

    /** The per-method record used by {@code dex-methods} (and reused by tests). */
    static Map<String, Object> describe(Method m, MethodImplementation impl) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("cls", m.getDefiningClass());
        r.put("name", m.getName());
        r.put("desc", Patch.descriptorOf(m));
        r.put("flags", m.getAccessFlags());
        r.put("rawSha256", Dex.rawSha256(m));
        r.put("semSha256", Dex.semSha256(m));
        r.put("registers", impl.getRegisterCount());
        r.put("ins", Dex.insRegisterCount(m));
        return r;
    }
}

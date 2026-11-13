package sgmo.helper;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds PairIP's string-holder fields.
 *
 * <p>PairIP replaces some string constants with reads of {@code public static String} fields in a
 * generated holder class; the fields are populated at runtime by its native VM (from
 * {@code StartupLauncher.launch()}). When that startup program is stubbed out, the fields stay
 * null and any class that reads one crashes as it loads. The patcher pre-fills them with the empty
 * string so nothing NPEs (telemetry/ads that lose their real strings are dropped offline anyway).
 *
 * <p>A holder is detected build-agnostically: a class with many ({@code >= MIN_FIELDS})
 * {@code public static} non-final {@code String} fields whose own {@code <clinit>} does not assign
 * them (so their value comes from outside the dex).
 */
final class Pairip {
    private Pairip() {}

    private static final int MIN_FIELDS = 10;

    /** A holder field to pre-fill. */
    static final class Str {
        final String cls;
        final String name;
        Str(String cls, String name) { this.cls = cls; this.name = name; }
    }

    static List<Str> findStringHolderFields(List<String> dexPaths) throws Exception {
        List<Str> out = new ArrayList<>();
        for (String p : dexPaths) {
            DexBackedDexFile dex = Dex.load(p);
            for (DexBackedClassDef c : dex.getClasses()) {
                List<String> candidate = new ArrayList<>();
                for (Field f : c.getStaticFields()) {
                    int fl = f.getAccessFlags();
                    boolean pub = (fl & AccessFlags.PUBLIC.getValue()) != 0;
                    boolean stat = (fl & AccessFlags.STATIC.getValue()) != 0;
                    boolean fin = (fl & AccessFlags.FINAL.getValue()) != 0;
                    if (pub && stat && !fin && "Ljava/lang/String;".equals(f.getType())) {
                        candidate.add(f.getName());
                    }
                }
                if (candidate.size() < MIN_FIELDS) {
                    continue;
                }
                if (assignsOwnStringFields(c)) {
                    continue; // a real constants class initialises its own fields
                }
                for (String name : candidate) {
                    out.add(new Str(c.getType(), name));
                }
            }
        }
        return out;
    }

    /** True if the class's <clinit> writes any of its own static String fields (sput-object). */
    private static boolean assignsOwnStringFields(DexBackedClassDef c) {
        for (Method m : c.getMethods()) {
            if (!"<clinit>".equals(m.getName()) || m.getImplementation() == null) {
                continue;
            }
            for (Instruction ins : m.getImplementation().getInstructions()) {
                if (ins instanceof ReferenceInstruction
                        && ins.getOpcode().name.startsWith("sput")) {
                    Object ref = ((ReferenceInstruction) ins).getReference();
                    if (ref instanceof FieldReference) {
                        FieldReference fr = (FieldReference) ref;
                        if (c.getType().equals(fr.getDefiningClass())
                                && "Ljava/lang/String;".equals(fr.getType())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** JSON for the {@code pairip-strings} command: {"fields":[{cls,name}], "count":n}. */
    static Map<String, Object> listCommand(List<String> dexPaths) throws Exception {
        List<Str> fields = findStringHolderFields(dexPaths);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Str s : fields) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cls", s.cls);
            m.put("name", s.name);
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", fields.size());
        out.put("fields", list);
        return out;
    }
}

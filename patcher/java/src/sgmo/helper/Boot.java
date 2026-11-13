package sgmo.helper;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Builds the tiny bootstrap class the patcher adds to the APK (as its own dex):
 *
 * <pre>
 *   package sgm.offline;
 *   public final class Boot {
 *       public static void start() {
 *           System.loadLibrary("sgmserver");
 *           nativeStart();
 *       }
 *       private static native void nativeStart();
 *   }
 * </pre>
 *
 * It references only {@code java.lang.System}, so it needs no Android types. The patcher injects a
 * single {@code invoke-static {}, Lsgm/offline/Boot;->start()V} at the top of the app's
 * {@code Application.attachBaseContext}; {@code nativeStart} is implemented by
 * {@code libsgmserver.so} ({@code Java_sgm_offline_Boot_nativeStart}).
 */
final class Boot {
    private Boot() {}

    static final String CLASS = "Lsgm/offline/Boot;";
    static final String START_INVOKE = "Lsgm/offline/Boot;->start()V";

    static byte[] buildDex() throws Exception {
        return buildDex(java.util.Collections.emptyList());
    }

    /** Build the Boot dex; {@code fill} fields (PairIP holder statics) are set to "" first. */
    static byte[] buildDex(List<Pairip.Str> fill) throws Exception {
        Opcodes opcodes = Opcodes.forApi(24);
        DexPool pool = new DexPool(opcodes);
        pool.internClass(bootClass(fill));
        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        return Arrays.copyOf(store.getBuffer(), store.getSize());
    }

    private static ImmutableClassDef bootClass(List<Pairip.Str> fill) {
        int pub = AccessFlags.PUBLIC.getValue();
        int stat = AccessFlags.STATIC.getValue();
        int fin = AccessFlags.FINAL.getValue();
        int nat = AccessFlags.NATIVE.getValue();
        int priv = AccessFlags.PRIVATE.getValue();

        // start(): pre-fill PairIP's null string-holder fields with "", then load the server.
        List<ImmutableInstruction> code = new ArrayList<>();
        if (fill != null && !fill.isEmpty()) {
            // const-string v0, ""   (once), then one sput-object per holder field
            code.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 0,
                    new ImmutableStringReference("")));
            for (Pairip.Str s : fill) {
                code.add(new com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                        Opcode.SPUT_OBJECT, 0,
                        new com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference(
                                s.cls, s.name, "Ljava/lang/String;")));
            }
        }
        // const-string v0, "sgmserver"
        code.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 0,
                new ImmutableStringReference("sgmserver")));
        // invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0,
                new ImmutableMethodReference("Ljava/lang/System;", "loadLibrary",
                        Collections.singletonList("Ljava/lang/String;"), "V")));
        // invoke-static {}, Lsgm/offline/Boot;->nativeStart()V
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                new ImmutableMethodReference(CLASS, "nativeStart", Collections.emptyList(), "V")));
        code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        ImmutableMethodImplementation startImpl = new ImmutableMethodImplementation(1, code, null, null);

        ImmutableMethod start = new ImmutableMethod(CLASS, "start",
                Collections.emptyList(), "V", pub | stat, null, null, startImpl);
        ImmutableMethod nativeStart = new ImmutableMethod(CLASS, "nativeStart",
                Collections.emptyList(), "V", priv | stat | nat, null, null, null);

        return new ImmutableClassDef(CLASS, pub | fin, "Ljava/lang/Object;", null, null, null,
                Collections.emptyList(), Arrays.asList(start, nativeStart));
    }
}

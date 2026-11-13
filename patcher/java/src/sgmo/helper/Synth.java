package sgmo.helper;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.Label;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31t;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a small, self-contained dex in memory: an {@code Activity} subclass whose
 * {@code onCreate} calls a static {@code VMRunner.invoke(String, Object[])} the way Google
 * PairIP does, a {@code BroadcastReceiver.onReceive}, a method that returns an object, and
 * untouched methods that exercise {@code const-string}, {@code invoke}, a packed switch and
 * a try/catch. The {@code selftest} command patches and compares this dex.
 */
final class Synth {
    private Synth() {}

    static final String PKG = "Lsgmo/synthetic/";
    static final String VMRUNNER = PKG + "VMRunner;";
    static final String ACTIVITY = PKG + "MyActivity;";
    static final String RECEIVER = PKG + "MyReceiver;";
    static final String UNTOUCHED = PKG + "Untouched;";

    private static final int ACC_PUBLIC = AccessFlags.PUBLIC.getValue();
    private static final int ACC_STATIC = AccessFlags.STATIC.getValue();
    private static final int ACC_PROTECTED = AccessFlags.PROTECTED.getValue();
    private static final int ACC_CONSTRUCTOR = AccessFlags.CONSTRUCTOR.getValue();

    /** Build the synthetic dex and return its bytes. */
    static byte[] build() throws Exception {
        Opcodes opcodes = Opcodes.forApi(24);
        DexPool pool = new DexPool(opcodes);
        pool.internClass(vmRunnerClass());
        pool.internClass(activityClass());
        pool.internClass(receiverClass());
        pool.internClass(untouchedClass());
        MemoryDataStore store = new MemoryDataStore();
        pool.writeTo(store);
        return java.util.Arrays.copyOf(store.getBuffer(), store.getSize());
    }

    // ---- classes -----------------------------------------------------------

    private static ImmutableClassDef vmRunnerClass() {
        // public static Object invoke(String, Object[]) { return null; }
        List<ImmutableInstruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0));
        code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        ImmutableMethod invoke = new ImmutableMethod(
                VMRUNNER, "invoke",
                params("Ljava/lang/String;", "[Ljava/lang/Object;"),
                "Ljava/lang/Object;",
                ACC_PUBLIC | ACC_STATIC, null, null,
                new ImmutableMethodImplementation(3, code, null, null));
        return new ImmutableClassDef(VMRUNNER, ACC_PUBLIC, "Ljava/lang/Object;",
                null, null, null, null, Collections.singletonList(invoke));
    }

    private static ImmutableClassDef activityClass() {
        List<ImmutableMethod> methods = new ArrayList<>();
        methods.add(defaultCtor(ACTIVITY, "Landroid/app/Activity;"));

        // protected void onCreate(Bundle) { VMRunner.invoke("WoePB2qxH76eOxLk", new Object[2]); }
        methods.add(new ImmutableMethod(ACTIVITY, "onCreate",
                params("Landroid/os/Bundle;"), "V", ACC_PROTECTED, null, null,
                vmRunnerCallBody(2, "WoePB2qxH76eOxLk", 4)));

        // protected void onDestroy() { VMRunner.invoke("UGoZfAAqqGND7qEw", new Object[0]); }
        methods.add(new ImmutableMethod(ACTIVITY, "onDestroy",
                null, "V", ACC_PROTECTED, null, null,
                vmRunnerCallBody(0, "UGoZfAAqqGND7qEw", 2)));

        // public Object getThing() { return new Object(); }
        List<ImmutableInstruction> gt = new ArrayList<>();
        gt.add(new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0,
                new ImmutableTypeReference("Ljava/lang/Object;")));
        gt.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                mref("Ljava/lang/Object;", "<init>", "V")));
        gt.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        methods.add(new ImmutableMethod(ACTIVITY, "getThing", null, "Ljava/lang/Object;",
                ACC_PUBLIC, null, null, new ImmutableMethodImplementation(2, gt, null, null)));

        return new ImmutableClassDef(ACTIVITY, ACC_PUBLIC, "Landroid/app/Activity;",
                null, null, null, null, methods);
    }

    private static ImmutableClassDef receiverClass() {
        List<ImmutableMethod> methods = new ArrayList<>();
        methods.add(defaultCtor(RECEIVER, "Landroid/content/BroadcastReceiver;"));

        // public void onReceive(Context, Intent) { const-string; Thread.yield(); }
        List<ImmutableInstruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 0,
                new ImmutableStringReference("received")));
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                mref("Ljava/lang/Thread;", "yield", "V")));
        code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        methods.add(new ImmutableMethod(RECEIVER, "onReceive",
                params("Landroid/content/Context;", "Landroid/content/Intent;"), "V",
                ACC_PUBLIC, null, null, new ImmutableMethodImplementation(4, code, null, null)));

        return new ImmutableClassDef(RECEIVER, ACC_PUBLIC, "Landroid/content/BroadcastReceiver;",
                null, null, null, null, methods);
    }

    private static ImmutableClassDef untouchedClass() {
        List<ImmutableMethod> methods = new ArrayList<>();

        // static void plain() { const-string; Thread.yield(); }
        List<ImmutableInstruction> plain = new ArrayList<>();
        plain.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 0,
                new ImmutableStringReference("plain")));
        plain.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                mref("Ljava/lang/Thread;", "yield", "V")));
        plain.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        methods.add(new ImmutableMethod(UNTOUCHED, "plain", null, "V",
                ACC_PUBLIC | ACC_STATIC, null, null,
                new ImmutableMethodImplementation(1, plain, null, null)));

        // static void switchy() { ... packed-switch + try/catch ... }
        methods.add(new ImmutableMethod(UNTOUCHED, "switchy", null, "V",
                ACC_PUBLIC | ACC_STATIC, null, null, switchyBody()));

        return new ImmutableClassDef(UNTOUCHED, ACC_PUBLIC, "Ljava/lang/Object;",
                null, null, null, null, methods);
    }

    // ---- bodies ------------------------------------------------------------

    private static ImmutableMethodImplementation vmRunnerCallBody(int arraySize, String asset, int regs) {
        List<ImmutableInstruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, arraySize));
        code.add(new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0,
                new ImmutableTypeReference("[Ljava/lang/Object;")));
        code.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference(asset)));
        List<String> invokeParams = new ArrayList<>();
        invokeParams.add("Ljava/lang/String;");
        invokeParams.add("[Ljava/lang/Object;");
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 1, 0, 0, 0, 0,
                new ImmutableMethodReference(VMRUNNER, "invoke", invokeParams, "Ljava/lang/Object;")));
        code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethodImplementation(regs, code, null, null);
    }

    /**
     * A packed switch and a try/catch, built so that every label points at an instruction
     * that already exists when the label is created (no forward references).
     */
    private static MethodImplementation switchyBody() {
        MutableMethodImplementation impl = new MutableMethodImplementation(2);
        // Case target bodies first, so their labels are backward references from the switch.
        impl.addInstruction(new BuilderInstruction10x(Opcode.NOP));                    // 0
        impl.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference("case0")));                                // 1
        impl.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference("case1")));                                // 2
        Label caseA = impl.newLabelForIndex(1);
        Label caseB = impl.newLabelForIndex(2);

        List<Label> targets = new ArrayList<>();
        targets.add(caseA);
        targets.add(caseB);
        impl.addInstruction(new BuilderPackedSwitchPayload(0, targets));               // 3
        Label payload = impl.newLabelForIndex(3);

        impl.addInstruction(new BuilderInstruction11n(Opcode.CONST_4, 0, 0));          // 4
        impl.addInstruction(new BuilderInstruction21c(Opcode.CONST_STRING, 1,
                new ImmutableStringReference("body")));                                 // 5 (try start)
        impl.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0,
                mref("Ljava/lang/Thread;", "yield", "V")));                            // 6
        impl.addInstruction(new BuilderInstruction31t(Opcode.PACKED_SWITCH, 0, payload)); // 7 (try end)
        impl.addInstruction(new BuilderInstruction11x(Opcode.MOVE_EXCEPTION, 0));      // 8 (handler)
        impl.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));            // 9

        Label tryStart = impl.newLabelForIndex(5);
        Label tryEnd = impl.newLabelForIndex(7);
        Label handler = impl.newLabelForIndex(8);
        impl.addCatch("Ljava/lang/Exception;", tryStart, tryEnd, handler);
        return impl;
    }

    // ---- helpers -----------------------------------------------------------

    private static ImmutableMethod defaultCtor(String cls, String superCls) {
        List<ImmutableInstruction> code = new ArrayList<>();
        code.add(new ImmutableInstruction35c(Opcode.INVOKE_DIRECT, 1, 0, 0, 0, 0, 0,
                mref(superCls, "<init>", "V")));
        code.add(new ImmutableInstruction10x(Opcode.RETURN_VOID));
        return new ImmutableMethod(cls, "<init>", null, "V",
                ACC_PUBLIC | ACC_CONSTRUCTOR, null, null,
                new ImmutableMethodImplementation(1, code, null, null));
    }

    private static List<ImmutableMethodParameter> params(String... types) {
        List<ImmutableMethodParameter> list = new ArrayList<>();
        for (String t : types) {
            list.add(new ImmutableMethodParameter(t, null, null));
        }
        return list;
    }

    /** No-argument method reference {@code cls->name()ret}. */
    private static Reference mref(String cls, String name, String ret) {
        return new ImmutableMethodReference(cls, name, new ArrayList<String>(), ret);
    }
}

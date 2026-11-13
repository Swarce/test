package sgmo.helper;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedMethodImplementation;
import com.android.tools.smali.dexlib2.iface.ExceptionHandler;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.HatLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.DualReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared dex helpers: loading a dex, computing the two per-method fingerprints,
 * and parsing/building method descriptors.
 *
 * <ul>
 *   <li><b>rawSha256</b> is the SHA-256 of a method's raw {@code insns} bytes exactly as
 *       stored in the input dex. It is byte-for-byte equal to androguard's
 *       {@code m.get_code().get_bc().get_raw()} for the same method.</li>
 *   <li><b>semSha256</b> is the SHA-256 of an index-independent rendering of the method
 *       body: register/ins/outs counts and every instruction with references resolved to
 *       strings, branch targets as relative code-unit offsets, payloads expanded and try
 *       blocks with handler types. It survives a dexlib2 rewrite that renumbers the pools.</li>
 * </ul>
 */
final class Dex {
    private Dex() {}

    /** Load a dex file from disk, keeping its own opcode set / dex version. */
    static DexBackedDexFile load(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return load(bytes);
    }

    /** Load a dex file from raw bytes, keeping its own opcode set / dex version. */
    static DexBackedDexFile load(byte[] bytes) {
        // Opcodes are selected from the dex version stored in the magic.
        return new DexBackedDexFile(null, bytes);
    }

    /** Lowercase hex SHA-256 of {@code data}. */
    static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Raw {@code insns} bytes of a method, exactly as stored in the input dex
     * (the {@code code_item}'s {@code insns} array, {@code insns_size*2} bytes,
     * starting at {@code code_item + 16}). Returns {@code null} for a method with no code.
     */
    static byte[] rawInsns(Method method) {
        MethodImplementation impl = method.getImplementation();
        if (!(impl instanceof DexBackedMethodImplementation)) {
            return null;
        }
        DexBackedMethodImplementation di = (DexBackedMethodImplementation) impl;
        try {
            // getInstructionsStartOffset() == codeOffset + 16; offsets index the data buffer.
            java.lang.reflect.Method m =
                    DexBackedMethodImplementation.class.getDeclaredMethod("getInstructionsStartOffset");
            m.setAccessible(true);
            int start = (Integer) m.invoke(di);
            int sizeCodeUnits = di.getInstructionsSize();
            Field dexField = DexBackedMethodImplementation.class.getField("dexFile");
            DexBackedDexFile dex = (DexBackedDexFile) dexField.get(di);
            return dex.getDataBuffer().readByteRange(start, sizeCodeUnits * 2);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("cannot read raw insns", e);
        }
    }

    /** rawSha256 of a method, or {@code null} for a method with no code. */
    static String rawSha256(Method method) {
        byte[] raw = rawInsns(method);
        return raw == null ? null : sha256Hex(raw);
    }

    /** Number of parameter registers, counting {@code this} for instance methods and 2 per wide type. */
    static int insRegisterCount(Method method) {
        int count = (method.getAccessFlags() & 0x8) != 0 ? 0 : 1; // ACC_STATIC
        for (CharSequence p : method.getParameterTypes()) {
            char c = p.charAt(0);
            count += (c == 'J' || c == 'D') ? 2 : 1;
        }
        return count;
    }

    /**
     * Index-independent rendering of a method body, hashed to the semSha256.
     * Returns a stable fingerprint for a method with code; {@code null} for an abstract/native method.
     */
    static String semSha256(Method method) {
        MethodImplementation impl = method.getImplementation();
        if (impl == null) {
            return null;
        }
        return sha256Hex(semRender(method, impl).getBytes(StandardCharsets.UTF_8));
    }

    /** The exact string that semSha256 hashes (exposed for tests/debugging). */
    static String semRender(Method method, MethodImplementation impl) {
        StringBuilder sb = new StringBuilder();
        int outs = 0;
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction ins : impl.getInstructions()) {
            instructions.add(ins);
        }
        for (Instruction ins : instructions) {
            if (ins instanceof RegisterRangeInstruction) {
                outs = Math.max(outs, ((RegisterRangeInstruction) ins).getRegisterCount());
            } else if (ins instanceof FiveRegisterInstruction) {
                outs = Math.max(outs, ((FiveRegisterInstruction) ins).getRegisterCount());
            }
        }
        sb.append("regs=").append(impl.getRegisterCount());
        sb.append(" ins=").append(insRegisterCount(method));
        sb.append(" outs=").append(outs);
        sb.append('\n');
        for (Instruction ins : instructions) {
            renderInstruction(sb, ins);
            sb.append('\n');
        }
        // Try blocks: start, length, and each handler's exception type + relative handler offset.
        for (TryBlock<? extends ExceptionHandler> tb : impl.getTryBlocks()) {
            sb.append("try start=").append(tb.getStartCodeAddress())
              .append(" count=").append(tb.getCodeUnitCount());
            for (ExceptionHandler h : tb.getExceptionHandlers()) {
                String type = h.getExceptionType();
                sb.append(" | handler type=").append(type == null ? "<any>" : type)
                  .append(" addr=").append(h.getHandlerCodeAddress());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void renderInstruction(StringBuilder sb, Instruction ins) {
        sb.append(ins.getOpcode().name);
        // Registers (part of the data flow, so they are kept verbatim).
        if (ins instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) ins;
            sb.append(" range{").append(r.getStartRegister()).append("..")
              .append(r.getStartRegister() + r.getRegisterCount() - 1).append('}');
        } else if (ins instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction r = (FiveRegisterInstruction) ins;
            sb.append(" regs{");
            int n = r.getRegisterCount();
            int[] regs = {r.getRegisterC(), r.getRegisterD(), r.getRegisterE(),
                          r.getRegisterF(), r.getRegisterG()};
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(regs[i]);
            }
            sb.append('}');
        } else if (ins instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) ins;
            sb.append(" v").append(r.getRegisterA()).append(",v").append(r.getRegisterB())
              .append(",v").append(r.getRegisterC());
        } else if (ins instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) ins;
            sb.append(" v").append(r.getRegisterA()).append(",v").append(r.getRegisterB());
        } else if (ins instanceof OneRegisterInstruction) {
            sb.append(" v").append(((OneRegisterInstruction) ins).getRegisterA());
        }
        // Literals.
        if (ins instanceof WideLiteralInstruction) {
            if (ins instanceof NarrowLiteralInstruction) {
                sb.append(" lit=").append(((NarrowLiteralInstruction) ins).getNarrowLiteral());
            } else {
                sb.append(" litw=").append(((WideLiteralInstruction) ins).getWideLiteral());
            }
        } else if (ins instanceof HatLiteralInstruction) {
            sb.append(" hat=").append(((HatLiteralInstruction) ins).getHatLiteral());
        }
        // Branch targets, rendered as relative code-unit offsets (already index-independent).
        if (ins instanceof OffsetInstruction) {
            sb.append(" off=").append(((OffsetInstruction) ins).getCodeOffset());
        }
        // References, rendered as strings so renumbering the pools does not change the hash.
        if (ins instanceof ReferenceInstruction) {
            sb.append(" ref=").append(renderReference(((ReferenceInstruction) ins).getReference()));
            if (ins instanceof DualReferenceInstruction) {
                sb.append(" ref2=").append(renderReference(((DualReferenceInstruction) ins).getReference2()));
            }
        }
        // Payloads.
        if (ins instanceof SwitchPayload) {
            sb.append(" switch[");
            boolean first = true;
            for (SwitchElement e : ((SwitchPayload) ins).getSwitchElements()) {
                if (!first) sb.append(';');
                first = false;
                sb.append(e.getKey()).append("->").append(e.getOffset());
            }
            sb.append(']');
        } else if (ins instanceof ArrayPayload) {
            ArrayPayload ap = (ArrayPayload) ins;
            sb.append(" array width=").append(ap.getElementWidth()).append('[');
            boolean first = true;
            for (Number el : ap.getArrayElements()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(el.longValue());
            }
            sb.append(']');
        }
    }

    /** Render a reference to a canonical, pool-independent string. */
    static String renderReference(Reference ref) {
        if (ref instanceof StringReference) {
            return "str:" + ((StringReference) ref).getString();
        }
        if (ref instanceof TypeReference) {
            return "type:" + ((TypeReference) ref).getType();
        }
        if (ref instanceof FieldReference) {
            FieldReference f = (FieldReference) ref;
            return "field:" + f.getDefiningClass() + "->" + f.getName() + ":" + f.getType();
        }
        if (ref instanceof MethodReference) {
            MethodReference m = (MethodReference) ref;
            return "method:" + descriptorOf(m);
        }
        return "ref:" + ref.toString();
    }

    /** {@code Lcls;->name(params)ret} for a method reference. */
    static String descriptorOf(MethodReference m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDefiningClass()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) {
            sb.append(p);
        }
        sb.append(')').append(m.getReturnType());
        return sb.toString();
    }

    /** Parse a method descriptor like {@code (Landroid/os/Bundle;I)V} into param types + return type. */
    static ParsedDescriptor parseDescriptor(String desc) {
        if (desc.charAt(0) != '(') {
            throw new IllegalArgumentException("bad descriptor: " + desc);
        }
        int close = desc.indexOf(')');
        List<String> params = new ArrayList<>();
        int i = 1;
        while (i < close) {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            char c = desc.charAt(i);
            if (c == 'L') {
                i = desc.indexOf(';', i) + 1;
            } else {
                i++;
            }
            params.add(desc.substring(start, i));
        }
        String ret = desc.substring(close + 1);
        return new ParsedDescriptor(params, ret);
    }

    /** A method descriptor split into parameter types and a return type. */
    static final class ParsedDescriptor {
        final List<String> params;
        final String returnType;

        ParsedDescriptor(List<String> params, String returnType) {
            this.params = params;
            this.returnType = returnType;
        }
    }
}

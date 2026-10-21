package dev.oreslang.runtime.compute;

import java.util.Arrays;
import java.util.Objects;

/**
 * Source-neutral portable compute IR used for backend conformance.
 *
 * <p>This is not a source AST and does not enable gpu/cpu/kernel syntax. It is
 * deliberately limited to homogeneous f32 elementwise kernels until compiler
 * effect admission exists.</p>
 */
public final class PortableKernelIR {
    private PortableKernelIR() {}

    public enum Layout { AOS, SOA, AOSOA }
    public enum Element { F32 }
    public enum Operation { SCALE, BIAS, SCALE_BIAS }

    public record Plan(
            Layout layout,
            long rows,
            int fields,
            int scalarBytes,
            int alignment,
            int lanes) {

        public Plan {
            Objects.requireNonNull(layout, "layout");
            if (rows < 0) {
                throw new IllegalArgumentException("rows must be non-negative");
            }
            if (fields <= 0
                    || !powerOfTwo(scalarBytes)
                    || !powerOfTwo(alignment)
                    || scalarBytes > alignment
                    || alignment > 4096) {
                throw new IllegalArgumentException(
                        "invalid homogeneous POD scalar width/alignment");
            }
            if (!powerOfTwo(lanes)
                    || lanes > 1024
                    || (layout != Layout.AOSOA && lanes != 1)) {
                throw new IllegalArgumentException("invalid AoSoA lane count");
            }
            // Force construction-time overflow validation.
            byteLength(layout, rows, fields, scalarBytes, alignment, lanes);
        }

        public long byteLength() {
            return byteLength(layout, rows, fields, scalarBytes, alignment, lanes);
        }

        public long byteOffset(long row, int field) {
            if (row < 0 || row >= rows || field < 0 || field >= fields) {
                throw new IndexOutOfBoundsException("portable kernel row/field out of bounds");
            }
            return switch (layout) {
                case AOS -> checkedAdd(
                        checkedMul(row, alignUp(checkedMul(fields, scalarBytes), alignment)),
                        checkedMul(field, scalarBytes));
                case SOA -> checkedAdd(
                        checkedMul(field, alignUp(checkedMul(rows, scalarBytes), alignment)),
                        checkedMul(row, scalarBytes));
                case AOSOA -> {
                    long tileBytes = alignUp(
                            checkedMul(checkedMul(fields, lanes), scalarBytes), alignment);
                    yield checkedAdd(
                            checkedAdd(
                                    checkedMul(row / lanes, tileBytes),
                                    checkedMul(checkedMul(field, lanes), scalarBytes)),
                            checkedMul(row % lanes, scalarBytes));
                }
            };
        }
    }

    public record Kernel(
            Plan layout,
            Element element,
            Operation operation,
            float scale,
            float bias) {

        public Kernel {
            Objects.requireNonNull(layout, "layout");
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(operation, "operation");
            if (element != Element.F32
                    || layout.scalarBytes() != Float.BYTES
                    || !Float.isFinite(scale)
                    || !Float.isFinite(bias)) {
                throw new IllegalArgumentException(
                        "only finite homogeneous f32 portable kernels are admitted");
            }
        }
    }

    /**
     * Owned host reference storage. This is a conformance backend, not a
     * hard-realtime allocation strategy and never an implicit GPU fallback.
     */
    public static final class HostBuffer {
        private final Plan layout;
        private final byte[] bytes;

        public HostBuffer(Plan layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            long byteLength = layout.byteLength();
            if (byteLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "CPU reference buffer exceeds Java array addressable range");
            }
            this.bytes = new byte[Math.toIntExact(byteLength)];
        }

        public Plan layout() {
            return layout;
        }

        public float read(long row, int field) {
            int offset = Math.toIntExact(layout.byteOffset(row, field));
            int bits = (bytes[offset] & 0xff)
                    | ((bytes[offset + 1] & 0xff) << 8)
                    | ((bytes[offset + 2] & 0xff) << 16)
                    | ((bytes[offset + 3] & 0xff) << 24);
            return Float.intBitsToFloat(bits);
        }

        public void write(long row, int field, float value) {
            int offset = Math.toIntExact(layout.byteOffset(row, field));
            int bits = Float.floatToRawIntBits(value);
            bytes[offset] = (byte) bits;
            bytes[offset + 1] = (byte) (bits >>> 8);
            bytes[offset + 2] = (byte) (bits >>> 16);
            bytes[offset + 3] = (byte) (bits >>> 24);
        }

        byte[] snapshotBytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    /** Explicit deterministic CPU conformance provider. */
    public static final class CpuReferenceProvider {
        private CpuReferenceProvider() {}

        public static void execute(Kernel kernel, HostBuffer input, HostBuffer output) {
            Objects.requireNonNull(kernel, "kernel");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(output, "output");
            if (input == output) {
                throw new IllegalArgumentException("portable kernel input/output alias forbidden");
            }
            if (!kernel.layout().equals(input.layout())
                    || !kernel.layout().equals(output.layout())) {
                throw new IllegalArgumentException("portable kernel layout mismatch");
            }

            Plan plan = kernel.layout();
            for (long row = 0; row < plan.rows(); row++) {
                for (int field = 0; field < plan.fields(); field++) {
                    float value = input.read(row, field);
                    float result;
                    switch (kernel.operation()) {
                        case SCALE -> result = value * kernel.scale();
                        case BIAS -> result = value + kernel.bias();
                        case SCALE_BIAS -> {
                            // Java's always-strict FP semantics require the
                            // intermediate f32 rounding; do not replace with Math.fma.
                            float product = value * kernel.scale();
                            result = product + kernel.bias();
                        }
                        default -> throw new IllegalStateException("unsupported portable kernel op");
                    }
                    output.write(row, field, result);
                }
            }
        }
    }

    private static boolean powerOfTwo(long value) {
        return value > 0 && (value & (value - 1)) == 0;
    }

    private static long checkedAdd(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("portable kernel sizes must be non-negative");
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("portable kernel byte length overflow", overflow);
        }
    }

    private static long checkedMul(long left, long right) {
        if (left < 0 || right < 0) {
            throw new IllegalArgumentException("portable kernel sizes must be non-negative");
        }
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("portable kernel byte length overflow", overflow);
        }
    }

    private static long alignUp(long value, long alignment) {
        if (value == 0) return 0;
        long groups = checkedAdd((value - 1) / alignment, 1);
        return checkedMul(groups, alignment);
    }

    private static long byteLength(
            Layout layout,
            long rows,
            int fields,
            int scalarBytes,
            int alignment,
            int lanes) {
        return switch (layout) {
            case AOS -> checkedMul(
                    rows,
                    alignUp(checkedMul(fields, scalarBytes), alignment));
            case SOA -> checkedMul(
                    fields,
                    alignUp(checkedMul(rows, scalarBytes), alignment));
            case AOSOA -> {
                long tiles = checkedAdd(rows / lanes, rows % lanes == 0 ? 0 : 1);
                long tileBytes = alignUp(
                        checkedMul(checkedMul(fields, lanes), scalarBytes), alignment);
                yield checkedMul(tiles, tileBytes);
            }
        };
    }
}

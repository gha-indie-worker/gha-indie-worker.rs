package dev.oreslang.runtime.compute;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;

/** Emits a stable bit-level transcript for cross-backend carrier comparison. */
public final class PortableKernelConformanceMain {
    private PortableKernelConformanceMain() {}

    public static void main(String[] args) {
        for (Layout layout : Layout.values()) {
            int lanes = layout == Layout.AOSOA ? 4 : 1;
            Plan plan = new Plan(layout, 5, 2, 4, 16, lanes);
            System.out.printf(
                    "layout=%d bytes=%d offsets=%d,%d,%d%n",
                    layout.ordinal(),
                    plan.byteLength(),
                    plan.byteOffset(0, 0),
                    plan.byteOffset(1, 1),
                    plan.byteOffset(4, 1));

            HostBuffer input = new HostBuffer(plan);
            HostBuffer output = new HostBuffer(plan);
            for (long row = 0; row < plan.rows(); row++) {
                for (int field = 0; field < plan.fields(); field++) {
                    input.write(row, field, (float) (row * 10 + field + 0.25));
                }
            }

            for (Operation op : Operation.values()) {
                Kernel kernel = new Kernel(plan, Element.F32, op, 1.5f, -2.0f);
                CpuReferenceProvider.execute(kernel, input, output);
                StringBuilder bits = new StringBuilder();
                for (long row = 0; row < plan.rows(); row++) {
                    for (int field = 0; field < plan.fields(); field++) {
                        if (!bits.isEmpty()) bits.append(',');
                        bits.append(String.format(
                                "%08x",
                                Float.floatToRawIntBits(output.read(row, field))));
                    }
                }
                System.out.printf(
                        "layout=%d op=%d bits=%s%n",
                        layout.ordinal(), op.ordinal(), bits);
            }
        }
    }
}

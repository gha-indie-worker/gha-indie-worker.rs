package dev.oreslang.runtime.compute;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;

/** Emits exact OpenCL artifact text for Java/native carrier parity. */
public final class PortableOpenClConformanceMain {
    private PortableOpenClConformanceMain() {}

    public static void main(String[] args) {
        for (Layout layout : Layout.values()) {
            int lanes = layout == Layout.AOSOA ? 4 : 1;
            Plan plan = new Plan(layout, 5, 2, 4, 16, lanes);
            for (Operation op : Operation.values()) {
                Kernel kernel = new Kernel(plan, Element.F32, op, 1.5f, -2.0f);
                OpenClArtifact artifact =
                        lowerOpenCl(kernel, new GpuTarget(64, 4096, 100, true));
                System.out.printf(
                        "layout=%d op=%d bytes=%d rows=%d fields=%d abi=%d addr=%d f32=%s le=%s%n",
                        layout.ordinal(),
                        op.ordinal(),
                        artifact.requiredBytes(),
                        artifact.globalRows(),
                        artifact.globalFields(),
                        artifact.artifactAbiVersion(),
                        artifact.requiredAddressBits(),
                        artifact.requiredFloat32(),
                        artifact.requiredLittleEndian());
                System.out.print(artifact.deviceSource());
                System.out.println("--");
            }
        }
    }
}

package dev.oreslang.runtime.compute;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;

public final class PortableKernelSmoke {
    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IndexOutOfBoundsException expected) {
            // fail closed
        }
    }

    public static void main(String[] args) {
        expectIllegal(() -> new Plan(Layout.AOS, -1, 1, 4, 4, 1), "negative rows accepted");
        expectIllegal(() -> new Plan(Layout.AOS, 1, 0, 4, 4, 1), "zero fields accepted");
        expectIllegal(() -> new Plan(Layout.AOS, 1, 1, 3, 4, 1), "non-power scalar accepted");
        expectIllegal(() -> new Plan(Layout.AOS, Long.MAX_VALUE, 2, 4, 4, 1), "overflow accepted");

        var plan = new Plan(Layout.AOSOA, 5, 2, 4, 16, 4);
        var input = new HostBuffer(plan);
        var output = new HostBuffer(plan);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);

        expectIllegal(() -> CpuReferenceProvider.execute(kernel, input, input), "alias accepted");
        expectIllegal(
                () -> new Kernel(plan, Element.F32, Operation.SCALE, Float.NaN, 0.0f),
                "NaN coefficient accepted");

        input.write(4, 1, 41.25f);
        CpuReferenceProvider.execute(kernel, input, output);
        float product = 41.25f * 1.5f;
        float expected = product - 2.0f;
        if (Float.floatToRawIntBits(expected)
                != Float.floatToRawIntBits(output.read(4, 1))) {
            throw new AssertionError("scale-bias reference rounding drift");
        }

        var one = new Plan(Layout.AOS, 1, 1, 4, 4, 1);
        var scale = new Kernel(one, Element.F32, Operation.SCALE, 2.0f, 0.0f);
        for (int iteration = 0; iteration < 100; iteration++) {
            var a = new HostBuffer(one);
            var b = new HostBuffer(one);
            a.write(0, 0, 1.0f);
            b.write(0, 0, 3.0f);
            final Throwable[] failure = new Throwable[1];
            Thread left = new Thread(() -> {
                try { CpuReferenceProvider.execute(scale, a, b); }
                catch (Throwable t) { failure[0] = t; }
            });
            Thread right = new Thread(() -> {
                try { CpuReferenceProvider.execute(scale, b, a); }
                catch (Throwable t) { failure[0] = t; }
            });
            left.start();
            right.start();
            try {
                left.join(5000);
                right.join(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            if (left.isAlive() || right.isAlive())
                throw new AssertionError("CPU reference reversed-pair deadlock");
            if (failure[0] != null)
                throw new AssertionError("CPU reference concurrency failed", failure[0]);
            float av = a.read(0, 0);
            float bv = b.read(0, 0);
            if (!((av == 4.0f && bv == 2.0f) || (av == 6.0f && bv == 12.0f)))
                throw new AssertionError("CPU reference execution was not serializable");
        }

        System.out.println("portable-kernel-g3-cpu-concurrency-smoke-ok");
    }
}

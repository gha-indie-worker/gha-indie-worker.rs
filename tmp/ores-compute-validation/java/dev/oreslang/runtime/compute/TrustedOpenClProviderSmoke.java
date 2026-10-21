package dev.oreslang.runtime.compute;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;

public final class TrustedOpenClProviderSmoke {
    private static final class ProgramHandle implements TrustedOpenClProvider.Program {}
    private static final class BufferHandle implements TrustedOpenClProvider.PhysicalBuffer {
        final byte[] bytes;
        BufferHandle(int size) { bytes = new byte[size]; }
    }
    private static final class EventHandle implements TrustedOpenClProvider.NativeEvent {
        TrustedOpenClProvider.EventStatus status;
        EventHandle(TrustedOpenClProvider.EventStatus status) { this.status = status; }
    }

    private static final class Driver implements TrustedOpenClProvider.Driver {
        boolean failUpload;
        boolean failKernel;
        boolean failReleaseBufferOnce;
        boolean failReleaseProgramOnce;
        boolean deviceLossQuiescent;
        final Deque<TrustedOpenClProvider.EventStatus> statuses = new ArrayDeque<>();
        EventHandle lastEvent;

        public TrustedOpenClProvider.Program prepare(OpenClArtifact artifact) {
            return new ProgramHandle();
        }
        public void releaseProgram(TrustedOpenClProvider.Program program) {
            if (failReleaseProgramOnce) {
                failReleaseProgramOnce = false;
                throw new IllegalStateException("program release failed");
            }
        }
        public TrustedOpenClProvider.PhysicalBuffer allocate(long bytes) {
            return new BufferHandle(Math.toIntExact(bytes));
        }
        public void releaseBuffer(TrustedOpenClProvider.PhysicalBuffer buffer) {
            if (failReleaseBufferOnce) {
                failReleaseBufferOnce = false;
                throw new IllegalStateException("release failed");
            }
        }
        public TrustedOpenClProvider.NativeEvent enqueueUpload(
                TrustedOpenClProvider.PhysicalBuffer destination, byte[] source) {
            if (failUpload) throw new IllegalStateException("upload failed");
            System.arraycopy(source, 0, ((BufferHandle) destination).bytes, 0, source.length);
            return event();
        }
        public TrustedOpenClProvider.NativeEvent enqueueKernel(
                TrustedOpenClProvider.Program program,
                TrustedOpenClProvider.PhysicalBuffer input,
                TrustedOpenClProvider.PhysicalBuffer output,
                long rows,
                int fields) {
            if (failKernel) throw new IllegalStateException("kernel failed");
            byte[] in = ((BufferHandle) input).bytes;
            byte[] out = ((BufferHandle) output).bytes;
            for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ 0x5a);
            return event();
        }
        public TrustedOpenClProvider.NativeEvent enqueueDownload(
                TrustedOpenClProvider.PhysicalBuffer source, byte[] destination) {
            System.arraycopy(((BufferHandle) source).bytes, 0, destination, 0, destination.length);
            return event();
        }
        public TrustedOpenClProvider.EventStatus poll(TrustedOpenClProvider.NativeEvent event) {
            return ((EventHandle) event).status;
        }
        public void releaseEvent(TrustedOpenClProvider.NativeEvent event) {}
        public boolean proveDeviceLossQuiescence() { return deviceLossQuiescent; }

        EventHandle event() {
            var status = statuses.isEmpty()
                    ? TrustedOpenClProvider.EventStatus.COMPLETE
                    : statuses.removeFirst();
            lastEvent = new EventHandle(status);
            return lastEvent;
        }
    }

    private record Fixture(
            ComputeDeviceHandle device,
            ComputeQueueHandle queue,
            ComputeBufferHandle<Byte> input,
            ComputeBufferHandle<Byte> output,
            Plan plan,
            Kernel kernel,
            byte[] host) {}

    private static Fixture fixture() {
        var device = ComputeAuthority.issueDevice("opencl", "gpu0", 1, "tenant-a");
        var queue = ComputeAuthority.issueQueue(device, 1);
        var plan = new Plan(Layout.AOS, 4, 2, 4, 16, 1);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);
        long bytes = plan.byteLength();
        var input = ComputeAuthority.<Byte>bindBuffer(device, 101, bytes, 16, "f32", "actor:1");
        var output = ComputeAuthority.<Byte>bindBuffer(device, 102, bytes, 16, "f32", "actor:1");
        byte[] host = new byte[Math.toIntExact(bytes)];
        for (int i = 0; i < host.length; i++) host[i] = (byte) i;
        return new Fixture(device, queue, input, output, plan, kernel, host);
    }

    private static TrustedOpenClProvider provider(Driver driver, Fixture f) {
        long bytes = f.plan.byteLength();
        return new TrustedOpenClProvider(
                driver,
                new TrustedOpenClProvider.Limits(bytes * 4, bytes * 4, 4, 4),
                f.queue,
                new TrustedOpenClProvider.DeviceProfile(
                        1, 64, bytes * 2, 100, 16, true, true));
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            // fail closed
        }
    }

    public static void main(String[] args) {
        {
            var f = fixture();
            var driver = new Driver();
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel);
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            if (provider.activeOperationCount() != 1) throw new AssertionError("operation not retained");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.KERNEL_SUBMITTED)
                throw new AssertionError("upload transition failed");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.DOWNLOAD_SUBMITTED)
                throw new AssertionError("kernel transition failed");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.SUCCEEDED)
                throw new AssertionError("download transition failed");
            if (provider.activeOperationCount() != 0) throw new AssertionError("terminal op retained");
            byte[] expected = Arrays.copyOf(f.host, f.host.length);
            for (int i = 0; i < expected.length; i++) expected[i] ^= 0x5a;
            if (!Arrays.equals(expected, operation.outputBytes()))
                throw new AssertionError("provider output mismatch");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.failUpload = true;
            driver.failReleaseBufferOnce = true;
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel);
            expectFailure(
                    () -> provider.begin(prepared, f.input, f.output, "actor:1", f.host),
                    "pre-enqueue cleanup failure was hidden");
            if (provider.usage().residentBytes() == 0)
                throw new AssertionError("pre-enqueue recovery lost quota authority");
            expectFailure(
                    () -> provider.begin(prepared, f.input, f.output, "actor:1", f.host),
                    "provider admitted work during recovery quarantine");
            provider.retryPreEnqueueCleanup();
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)))
                throw new AssertionError("pre-enqueue recovery leaked quota");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.failReleaseProgramOnce = true;
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel);
            expectFailure(prepared::close, "program release failure was hidden");
            if (prepared.artifact().artifactAbiVersion() != 1)
                throw new AssertionError("failed release dropped prepared authority");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.statuses.add(TrustedOpenClProvider.EventStatus.DEVICE_LOST);
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel);
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.DEVICE_LOST)
                throw new AssertionError("device loss not surfaced");
            if (provider.usage().residentBytes() == 0 || provider.activeOperationCount() != 1)
                throw new AssertionError("device loss released resources without quiescence");
            expectFailure(() -> provider.recoverDeviceLoss(operation), "unproven loss recovery accepted");
            driver.deviceLossQuiescent = true;
            provider.recoverDeviceLoss(operation);
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0))
                    || provider.activeOperationCount() != 0)
                throw new AssertionError("proved device-loss recovery leaked authority");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            long bytes = f.plan.byteLength();
            var provider = new TrustedOpenClProvider(
                    driver,
                    new TrustedOpenClProvider.Limits(bytes * 4, bytes * 4, 4, 4),
                    f.queue,
                    new TrustedOpenClProvider.DeviceProfile(
                            1, 64, bytes * 2, 100, 1, true, true));
            expectFailure(() -> provider.prepare(f.kernel), "field bound bypassed physical ABI");
        }

        System.out.println("trusted-opencl-provider-g3-hardened-smoke-ok");
    }
}

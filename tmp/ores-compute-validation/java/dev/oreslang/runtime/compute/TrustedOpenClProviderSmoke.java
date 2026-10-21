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
        final Deque<TrustedOpenClProvider.EventStatus> statuses = new ArrayDeque<>();
        EventHandle lastEvent;

        public TrustedOpenClProvider.Program prepare(OpenClArtifact artifact) {
            return new ProgramHandle();
        }
        public void releaseProgram(TrustedOpenClProvider.Program program) {}
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
            ComputeBufferHandle<Byte> input,
            ComputeBufferHandle<Byte> output,
            Plan plan,
            Kernel kernel,
            byte[] host) {}

    private static Fixture fixture() {
        var device = ComputeAuthority.issueDevice("opencl", "gpu0", 1, "tenant-a");
        var plan = new Plan(Layout.AOS, 4, 2, 4, 16, 1);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);
        long bytes = plan.byteLength();
        var input = ComputeAuthority.<Byte>bindBuffer(device, 101, bytes, 16, "f32", "actor:1");
        var output = ComputeAuthority.<Byte>bindBuffer(device, 102, bytes, 16, "f32", "actor:1");
        byte[] host = new byte[Math.toIntExact(bytes)];
        for (int i = 0; i < host.length; i++) host[i] = (byte) i;
        return new Fixture(device, input, output, plan, kernel, host);
    }

    private static TrustedOpenClProvider provider(Driver driver, Fixture f) {
        long bytes = f.plan.byteLength();
        return new TrustedOpenClProvider(
                driver,
                new TrustedOpenClProvider.Limits(bytes * 4, bytes * 4, 4, 4),
                f.device);
    }

    private static GpuTarget target(Fixture f) {
        return new GpuTarget(64, f.plan.byteLength() * 2, 100, true);
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
            var prepared = provider.prepare(f.kernel, target(f));
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            expectFailure(prepared::close, "prepared kernel closed while active");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.KERNEL_SUBMITTED)
                throw new AssertionError("upload transition failed");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.DOWNLOAD_SUBMITTED)
                throw new AssertionError("kernel transition failed");
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.SUCCEEDED)
                throw new AssertionError("download transition failed");
            byte[] expected = Arrays.copyOf(f.host, f.host.length);
            for (int i = 0; i < expected.length; i++) expected[i] ^= 0x5a;
            if (!Arrays.equals(expected, operation.outputBytes()))
                throw new AssertionError("provider output mismatch");
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)))
                throw new AssertionError("successful provider leaked quota");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.failUpload = true;
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel, target(f));
            expectFailure(
                    () -> provider.begin(prepared, f.input, f.output, "actor:1", f.host),
                    "first enqueue failure accepted");
            if (f.input.state() != ComputeBufferHandle.State.IDLE
                    || f.output.state() != ComputeBufferHandle.State.IDLE)
                throw new AssertionError("pre-enqueue failure did not restore pair");
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)))
                throw new AssertionError("pre-enqueue failure leaked quota");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.failKernel = true;
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel, target(f));
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            expectFailure(() -> provider.poll(operation), "post-submit kernel failure accepted");
            if (f.input.state() != ComputeBufferHandle.State.POISONED
                    || f.output.state() != ComputeBufferHandle.State.POISONED)
                throw new AssertionError("post-submit failure did not poison pair");
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)))
                throw new AssertionError("post-submit terminal failure leaked quota");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            driver.statuses.add(TrustedOpenClProvider.EventStatus.PENDING);
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel, target(f));
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            provider.requestTimeoutCancellation(operation);
            var before = provider.usage();
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.CANCEL_REQUESTED)
                throw new AssertionError("pending timeout changed to terminal state");
            if (!before.equals(provider.usage()))
                throw new AssertionError("pending timeout released physical quota");
            driver.lastEvent.status = TrustedOpenClProvider.EventStatus.COMPLETE;
            if (provider.poll(operation) != TrustedOpenClProvider.OperationState.CANCELLED)
                throw new AssertionError("quiescent cancellation not acknowledged");
            if (!provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)))
                throw new AssertionError("cancelled provider leaked quota");
            prepared.close();
        }

        {
            var f = fixture();
            var driver = new Driver();
            var provider = provider(driver, f);
            var prepared = provider.prepare(f.kernel, target(f));
            var operation = provider.begin(prepared, f.input, f.output, "actor:1", f.host);
            provider.poll(operation);
            provider.poll(operation);
            driver.failReleaseBufferOnce = true;
            expectFailure(() -> provider.poll(operation), "release failure was hidden");
            if (operation.state() != TrustedOpenClProvider.OperationState.RELEASE_FAILED)
                throw new AssertionError("release failure not retained");
            if (provider.usage().residentBytes() == 0)
                throw new AssertionError("release failure incorrectly freed quota");
            provider.retryRelease(operation);
            if (operation.state() != TrustedOpenClProvider.OperationState.SUCCEEDED)
                throw new AssertionError("release retry did not restore terminal state");
            prepared.close();
        }

        System.out.println("trusted-opencl-provider-g3-smoke-ok");
    }
}

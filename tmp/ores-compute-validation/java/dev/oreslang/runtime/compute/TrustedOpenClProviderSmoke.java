package dev.oreslang.runtime.compute;

import java.util.ArrayDeque;
import java.util.Deque;

import static dev.oreslang.runtime.compute.PortableKernelIR.*;

public final class TrustedOpenClProviderSmoke {
    private static final class ProgramImpl implements TrustedOpenClProvider.Program {}
    private static final class BufferImpl implements TrustedOpenClProvider.PhysicalBuffer {
        final byte[] bytes;
        BufferImpl(int size) { bytes = new byte[size]; }
    }
    private static final class EventImpl implements TrustedOpenClProvider.NativeEvent {
        TrustedOpenClProvider.EventStatus status;
        EventImpl(TrustedOpenClProvider.EventStatus status) { this.status = status; }
    }
    private static final class DriverImpl implements TrustedOpenClProvider.Driver {
        final Deque<TrustedOpenClProvider.EventStatus> statuses = new ArrayDeque<>();
        EventImpl lastEvent;
        boolean deviceLossQuiescent;
        int releasedEvents;

        public TrustedOpenClProvider.Program prepare(OpenClArtifact artifact) {
            return new ProgramImpl();
        }
        public void releaseProgram(TrustedOpenClProvider.Program program) {}
        public TrustedOpenClProvider.PhysicalBuffer allocate(long bytes) {
            return new BufferImpl(Math.toIntExact(bytes));
        }
        public void releaseBuffer(TrustedOpenClProvider.PhysicalBuffer buffer) {}
        public TrustedOpenClProvider.NativeEvent enqueueUpload(
                TrustedOpenClProvider.PhysicalBuffer destination, byte[] source) {
            System.arraycopy(source, 0, ((BufferImpl) destination).bytes, 0, source.length);
            return nextEvent();
        }
        public TrustedOpenClProvider.NativeEvent enqueueKernel(
                TrustedOpenClProvider.Program program,
                TrustedOpenClProvider.PhysicalBuffer input,
                TrustedOpenClProvider.PhysicalBuffer output,
                long globalRows,
                int globalFields) {
            byte[] in = ((BufferImpl) input).bytes;
            byte[] out = ((BufferImpl) output).bytes;
            for (int i = 0; i < in.length; i++) out[i] = (byte) (in[i] ^ 0x5a);
            return nextEvent();
        }
        public TrustedOpenClProvider.NativeEvent enqueueDownload(
                TrustedOpenClProvider.PhysicalBuffer source, byte[] destination) {
            System.arraycopy(((BufferImpl) source).bytes, 0, destination, 0, destination.length);
            return nextEvent();
        }
        public TrustedOpenClProvider.EventStatus poll(TrustedOpenClProvider.NativeEvent event) {
            return ((EventImpl) event).status;
        }
        public void releaseEvent(TrustedOpenClProvider.NativeEvent event) {
            releasedEvents++;
        }
        public boolean proveDeviceLossQuiescence() { return deviceLossQuiescent; }

        EventImpl nextEvent() {
            var status = statuses.isEmpty()
                    ? TrustedOpenClProvider.EventStatus.COMPLETE
                    : statuses.removeFirst();
            lastEvent = new EventImpl(status);
            return lastEvent;
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        var device = ComputeAuthority.issueDevice("opencl", "gpu0", 1, "tenant-a");
        var queue = ComputeAuthority.issueQueue(device, 1);
        var plan = new Plan(Layout.AOS, 4, 2, 4, 16, 1);
        var kernel = new Kernel(plan, Element.F32, Operation.SCALE_BIAS, 1.5f, -2.0f);
        long bytes = plan.byteLength();
        var input = ComputeAuthority.<Byte>bindBuffer(
                device, 101, bytes, 16, "f32", "actor:1");
        var output = ComputeAuthority.<Byte>bindBuffer(
                device, 102, bytes, 16, "f32", "actor:1");
        byte[] host = new byte[Math.toIntExact(bytes)];
        for (int i = 0; i < host.length; i++) host[i] = (byte) i;

        var driver = new DriverImpl();
        driver.statuses.add(TrustedOpenClProvider.EventStatus.PENDING);
        var provider = new TrustedOpenClProvider(
                driver,
                new TrustedOpenClProvider.Limits(bytes * 4, bytes * 4, 4, 4),
                queue,
                new TrustedOpenClProvider.DeviceProfile(
                        1, 64, bytes * 2, 100, 16, true, true));
        var prepared = provider.prepare(kernel);
        var operation = provider.begin(prepared, input, output, "actor:1", host);

        provider.requestTimeoutCancellation(operation);
        var before = provider.usage();
        check(provider.poll(operation) == TrustedOpenClProvider.OperationState.CANCEL_REQUESTED,
                "pending cancellation did not remain pending");
        check(driver.releasedEvents == 0, "pending event released too early");

        driver.lastEvent.status = TrustedOpenClProvider.EventStatus.DEVICE_LOST;
        check(provider.poll(operation) == TrustedOpenClProvider.OperationState.DEVICE_LOST,
                "device loss was incorrectly collapsed into cancellation");
        check(provider.usage().equals(before), "device loss released quota");
        check(driver.releasedEvents == 0, "lost-device event released before recovery proof");
        check(input.state() == ComputeBufferHandle.State.POISONED, "input not poisoned");
        check(output.state() == ComputeBufferHandle.State.POISONED, "output not poisoned");

        boolean refused = false;
        try { provider.recoverDeviceLoss(operation); }
        catch (IllegalStateException expected) { refused = true; }
        check(refused, "device-loss recovery succeeded without proof");
        check(driver.releasedEvents == 0, "lost event released after failed recovery");

        driver.deviceLossQuiescent = true;
        provider.recoverDeviceLoss(operation);
        check(driver.releasedEvents == 1, "lost event not released after recovery proof");
        check(provider.usage().equals(new TrustedOpenClProvider.Usage(0, 0, 0, 0)),
                "quota retained after proven recovery");
        check(provider.activeOperationCount() == 0, "active operation leaked");

        ComputeAuthority.retire(input, device, "actor:1");
        ComputeAuthority.retire(output, device, "actor:1");
        prepared.close();

        System.out.println("java-g3-cancel-device-loss-hardening-ok");
    }
}

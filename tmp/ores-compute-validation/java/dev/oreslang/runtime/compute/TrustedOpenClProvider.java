package dev.oreslang.runtime.compute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static dev.oreslang.runtime.compute.PortableKernelIR.GpuTarget;
import static dev.oreslang.runtime.compute.PortableKernelIR.Kernel;
import static dev.oreslang.runtime.compute.PortableKernelIR.OpenClArtifact;
import static dev.oreslang.runtime.compute.PortableKernelIR.lowerOpenCl;

/**
 * Trusted G3 provider transaction.
 *
 * <p>This class is package-private on purpose. It is runtime plumbing, not a
 * guest/source API and not a ServiceLoader/reflection extension point.</p>
 */
final class TrustedOpenClProvider {
    enum EventStatus { PENDING, COMPLETE, FAILED, DEVICE_LOST }

    enum OperationState {
        RESERVED,
        UPLOAD_SUBMITTED,
        KERNEL_SUBMITTED,
        DOWNLOAD_SUBMITTED,
        CANCEL_REQUESTED,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        DEVICE_LOST,
        RELEASE_FAILED
    }

    interface Program {}
    interface PhysicalBuffer {}
    interface NativeEvent {}

    interface Driver {
        Program prepare(OpenClArtifact artifact);
        void releaseProgram(Program program);

        PhysicalBuffer allocate(long bytes);
        void releaseBuffer(PhysicalBuffer buffer);

        NativeEvent enqueueUpload(
                PhysicalBuffer destination,
                byte[] source);
        NativeEvent enqueueKernel(
                Program program,
                PhysicalBuffer input,
                PhysicalBuffer output,
                long globalRows,
                int globalFields);
        NativeEvent enqueueDownload(
                PhysicalBuffer source,
                byte[] destination);

        EventStatus poll(NativeEvent event);
        void releaseEvent(NativeEvent event);
    }

    record Limits(
            long residentBytes,
            long pinnedHostBytes,
            int outstandingEvents,
            int queueSlots) {
        Limits {
            if (residentBytes <= 0
                    || pinnedHostBytes <= 0
                    || outstandingEvents <= 0
                    || queueSlots <= 0) {
                throw new IllegalArgumentException("compute provider quotas must be positive");
            }
        }
    }

    record Usage(
            long residentBytes,
            long pinnedHostBytes,
            int outstandingEvents,
            int queueSlots) {}

    static final class PreparedKernel implements AutoCloseable {
        private final TrustedOpenClProvider owner;
        private final OpenClArtifact artifact;
        private Program program;
        private int activeOperations;

        PreparedKernel(
                TrustedOpenClProvider owner,
                OpenClArtifact artifact,
                Program program) {
            this.owner = owner;
            this.artifact = artifact;
            this.program = program;
        }

        OpenClArtifact artifact() {
            synchronized (this) {
                if (program == null) {
                    throw new IllegalStateException("prepared kernel already closed");
                }
                return artifact;
            }
        }

        Program program() {
            synchronized (this) {
                if (program == null) {
                    throw new IllegalStateException("prepared kernel already closed");
                }
                return program;
            }
        }

        void retainOperation() {
            synchronized (this) {
                if (program == null) {
                    throw new IllegalStateException("prepared kernel already closed");
                }
                if (activeOperations == Integer.MAX_VALUE) {
                    throw new IllegalStateException("prepared kernel operation count exhausted");
                }
                activeOperations++;
            }
        }

        void releaseOperation() {
            synchronized (this) {
                if (activeOperations <= 0) {
                    throw new IllegalStateException("prepared kernel operation underflow");
                }
                activeOperations--;
            }
        }

        @Override
        public void close() {
            Program held;
            synchronized (this) {
                if (program == null) return;
                if (activeOperations != 0) {
                    throw new IllegalStateException(
                            "prepared kernel still has active provider operations");
                }
                held = program;
                program = null;
            }
            owner.driver.releaseProgram(held);
        }
    }

    static final class Operation {
        private final TrustedOpenClProvider owner;
        private final PreparedKernel prepared;
        private final ComputeBufferHandle<?> inputAuthority;
        private final ComputeBufferHandle<?> outputAuthority;
        private final ComputeFencePair fencePair;
        private final Lease lease;
        private final byte[] inputStaging;
        private final byte[] outputStaging;
        private PhysicalBuffer inputPhysical;
        private PhysicalBuffer outputPhysical;
        private NativeEvent event;
        private OperationState state;
        private boolean cancelRequested;
        private boolean timeoutRequested;
        private boolean cleanupComplete;
        private OperationState terminalBeforeReleaseFailure;

        private Operation(
                TrustedOpenClProvider owner,
                PreparedKernel prepared,
                ComputeBufferHandle<?> inputAuthority,
                ComputeBufferHandle<?> outputAuthority,
                ComputeFencePair fencePair,
                Lease lease,
                byte[] inputStaging,
                byte[] outputStaging,
                PhysicalBuffer inputPhysical,
                PhysicalBuffer outputPhysical,
                NativeEvent event) {
            this.owner = owner;
            this.prepared = prepared;
            this.inputAuthority = inputAuthority;
            this.outputAuthority = outputAuthority;
            this.fencePair = fencePair;
            this.lease = lease;
            this.inputStaging = inputStaging;
            this.outputStaging = outputStaging;
            this.inputPhysical = inputPhysical;
            this.outputPhysical = outputPhysical;
            this.event = event;
            this.state = event == null
                    ? OperationState.RESERVED
                    : OperationState.UPLOAD_SUBMITTED;
        }

        synchronized OperationState state() {
            return state;
        }

        synchronized boolean timeoutRequested() {
            return timeoutRequested;
        }

        synchronized byte[] outputBytes() {
            if (state != OperationState.SUCCEEDED || !cleanupComplete) {
                throw new IllegalStateException("provider output is not ready");
            }
            return Arrays.copyOf(outputStaging, outputStaging.length);
        }
    }

    private final Driver driver;
    private final Limits limits;
    private final ComputeDeviceHandle deviceAuthority;

    private long residentBytes;
    private long pinnedHostBytes;
    private int outstandingEvents;
    private int queueSlots;
    private final List<Operation> quarantinedOperations = new ArrayList<>();

    TrustedOpenClProvider(
            Driver driver,
            Limits limits,
            ComputeDeviceHandle deviceAuthority) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.deviceAuthority = Objects.requireNonNull(
                deviceAuthority, "deviceAuthority");
    }

    PreparedKernel prepare(Kernel kernel, GpuTarget target) {
        OpenClArtifact artifact = lowerOpenCl(kernel, target);
        Program program = Objects.requireNonNull(
                driver.prepare(artifact),
                "OpenCL driver returned null program");
        return new PreparedKernel(this, artifact, program);
    }

    Operation begin(
            PreparedKernel prepared,
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            String expectedOwnerDomain,
            byte[] hostInput) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(expectedOwnerDomain, "expectedOwnerDomain");
        Objects.requireNonNull(hostInput, "hostInput");
        synchronized (this) {
            if (!quarantinedOperations.isEmpty()) {
                throw new IllegalStateException(
                        "compute provider quarantined after post-enqueue invariant failure");
            }
        }

        OpenClArtifact artifact = prepared.artifact();
        long bytes = artifact.requiredBytes();
        if (bytes <= 0
                || bytes > Integer.MAX_VALUE
                || hostInput.length != Math.toIntExact(bytes)
                || input.byteLength() != bytes
                || output.byteLength() != bytes) {
            throw new IllegalArgumentException(
                    "provider host/device bytes do not match admitted kernel");
        }

        long resident = checkedMultiply(bytes, 2);
        long pinned = checkedMultiply(bytes, 2);
        Lease lease = reserve(resident, pinned, 1, 1);
        try {
            prepared.retainOperation();
        } catch (RuntimeException retainFailure) {
            lease.release();
            throw retainFailure;
        }

        PhysicalBuffer inputPhysical = null;
        PhysicalBuffer outputPhysical = null;
        ComputeFencePair pair = null;
        Operation operation = null;
        boolean durableEnqueued = false;
        try {
            inputPhysical = Objects.requireNonNull(
                    driver.allocate(bytes),
                    "OpenCL driver returned null input buffer");
            outputPhysical = Objects.requireNonNull(
                    driver.allocate(bytes),
                    "OpenCL driver returned null output buffer");

            pair = ComputeAuthority.beginKernelPair(
                    input,
                    output,
                    deviceAuthority,
                    expectedOwnerDomain,
                    bytes);

            byte[] inputStaging = Arrays.copyOf(hostInput, hostInput.length);
            byte[] outputStaging = new byte[hostInput.length];

            // Allocate every Java-side object needed to resume the operation
            // BEFORE the first physical enqueue. After enqueue returns an
            // event, the path to a resumable operation is allocation-free.
            operation = new Operation(
                    this,
                    prepared,
                    input,
                    output,
                    pair,
                    lease,
                    inputStaging,
                    outputStaging,
                    inputPhysical,
                    outputPhysical,
                    null);

            NativeEvent upload = Objects.requireNonNull(
                    driver.enqueueUpload(inputPhysical, inputStaging),
                    "OpenCL driver returned null upload event");
            operation.event = upload;
            durableEnqueued = true;

            // Returning a provider-owned event is the durable-enqueue witness.
            // This transition performs no allocation.
            ComputeAuthority.markPairSubmitted(input, output, pair);
            operation.state = OperationState.UPLOAD_SUBMITTED;
            return operation;
        } catch (RuntimeException | Error failure) {
            if (durableEnqueued && operation != null) {
                // Hardware may own the buffers. Retain every capability and
                // quota lease; pre-enqueue rollback is now forbidden.
                synchronized (this) {
                    quarantinedOperations.add(operation);
                }
                throw failure;
            }
            if (pair != null) {
                try {
                    ComputeAuthority.abortPairBeforeEnqueue(input, output, pair);
                } catch (RuntimeException abortFailure) {
                    failure.addSuppressed(abortFailure);
                }
            }
            boolean released = releasePhysicalQuietly(inputPhysical, outputPhysical, failure);
            if (released) {
                lease.release();
            }
            prepared.releaseOperation();
            throw failure;
        }
    }

    OperationState poll(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (operation) {
            requireOwner(operation);
            if (terminal(operation.state)) {
                return operation.state;
            }
            if (operation.state == OperationState.RESERVED) {
                throw new IllegalStateException(
                        "reserved compute operation was never physically submitted");
            }

            EventStatus status = Objects.requireNonNull(
                    driver.poll(operation.event),
                    "OpenCL driver returned null event status");
            if (status == EventStatus.PENDING) {
                return operation.state;
            }

            NativeEvent completedEvent = operation.event;
            driver.releaseEvent(completedEvent);
            operation.event = null;

            if (operation.cancelRequested) {
                ComputeAuthority.acknowledgeCancelledPair(
                        operation.inputAuthority,
                        operation.outputAuthority,
                        operation.fencePair);
                operation.state = OperationState.CANCELLED;
                finishCleanup(operation);
                return operation.state;
            }

            if (status == EventStatus.DEVICE_LOST) {
                ComputeAuthority.completePair(
                        operation.inputAuthority,
                        operation.outputAuthority,
                        operation.fencePair,
                        false);
                operation.state = OperationState.DEVICE_LOST;
                finishCleanup(operation);
                return operation.state;
            }

            if (status == EventStatus.FAILED) {
                ComputeAuthority.completePair(
                        operation.inputAuthority,
                        operation.outputAuthority,
                        operation.fencePair,
                        false);
                operation.state = OperationState.FAILED;
                finishCleanup(operation);
                return operation.state;
            }

            try {
                switch (operation.state) {
                    case UPLOAD_SUBMITTED -> {
                        operation.event = Objects.requireNonNull(
                                driver.enqueueKernel(
                                        operation.prepared.program(),
                                        operation.inputPhysical,
                                        operation.outputPhysical,
                                        operation.prepared.artifact().globalRows(),
                                        operation.prepared.artifact().globalFields()),
                                "OpenCL driver returned null kernel event");
                        operation.state = OperationState.KERNEL_SUBMITTED;
                    }
                    case KERNEL_SUBMITTED -> {
                        operation.event = Objects.requireNonNull(
                                driver.enqueueDownload(
                                        operation.outputPhysical,
                                        operation.outputStaging),
                                "OpenCL driver returned null download event");
                        operation.state = OperationState.DOWNLOAD_SUBMITTED;
                    }
                    case DOWNLOAD_SUBMITTED -> {
                        ComputeAuthority.completePair(
                                operation.inputAuthority,
                                operation.outputAuthority,
                                operation.fencePair,
                                true);
                        operation.state = OperationState.SUCCEEDED;
                        finishCleanup(operation);
                    }
                    default -> throw new IllegalStateException(
                            "invalid provider transition from " + operation.state);
                }
            } catch (RuntimeException postSubmitFailure) {
                // The prior event is terminal and the next enqueue never
                // returned an event, so hardware is quiescent for this launch.
                ComputeAuthority.completePair(
                        operation.inputAuthority,
                        operation.outputAuthority,
                        operation.fencePair,
                        false);
                operation.state = OperationState.FAILED;
                try {
                    finishCleanup(operation);
                } catch (RuntimeException cleanupFailure) {
                    postSubmitFailure.addSuppressed(cleanupFailure);
                }
                throw postSubmitFailure;
            }
            return operation.state;
        }
    }

    void requestCancel(Operation operation) {
        requestCancel(operation, false);
    }

    void requestTimeoutCancellation(Operation operation) {
        requestCancel(operation, true);
    }

    Usage usage() {
        synchronized (this) {
            return new Usage(
                    residentBytes,
                    pinnedHostBytes,
                    outstandingEvents,
                    queueSlots);
        }
    }

    void retryRelease(Operation operation) {
        Objects.requireNonNull(operation, "operation");
        synchronized (operation) {
            requireOwner(operation);
            if (operation.state != OperationState.RELEASE_FAILED) {
                throw new IllegalStateException("operation has no failed physical release");
            }
            finishCleanup(operation);
        }
    }

    private void requestCancel(Operation operation, boolean timeout) {
        Objects.requireNonNull(operation, "operation");
        synchronized (operation) {
            requireOwner(operation);
            if (terminal(operation.state)) {
                throw new IllegalStateException("provider operation already terminal");
            }
            if (!operation.cancelRequested) {
                ComputeAuthority.cancelPair(
                        operation.inputAuthority,
                        operation.outputAuthority,
                        operation.fencePair);
                operation.cancelRequested = true;
                operation.state = OperationState.CANCEL_REQUESTED;
            }
            operation.timeoutRequested |= timeout;
        }
    }

    private void finishCleanup(Operation operation) {
        RuntimeException releaseFailure = null;
        try {
            if (operation.inputPhysical != null) {
                driver.releaseBuffer(operation.inputPhysical);
                operation.inputPhysical = null;
            }
        } catch (RuntimeException failure) {
            releaseFailure = failure;
        }

        try {
            if (operation.outputPhysical != null) {
                driver.releaseBuffer(operation.outputPhysical);
                operation.outputPhysical = null;
            }
        } catch (RuntimeException failure) {
            if (releaseFailure == null) releaseFailure = failure;
            else releaseFailure.addSuppressed(failure);
        }

        if (releaseFailure != null) {
            if (operation.state != OperationState.RELEASE_FAILED) {
                operation.terminalBeforeReleaseFailure = operation.state;
            }
            operation.state = OperationState.RELEASE_FAILED;
            throw releaseFailure;
        }

        if (!operation.cleanupComplete) {
            operation.lease.release();
            operation.prepared.releaseOperation();
            operation.cleanupComplete = true;
        }
        if (operation.state == OperationState.RELEASE_FAILED) {
            if (operation.terminalBeforeReleaseFailure == null) {
                throw new IllegalStateException("missing terminal state for provider release retry");
            }
            operation.state = operation.terminalBeforeReleaseFailure;
            operation.terminalBeforeReleaseFailure = null;
        }
    }

    private boolean releasePhysicalQuietly(
            PhysicalBuffer input,
            PhysicalBuffer output,
            RuntimeException original) {
        boolean released = true;
        if (input != null) {
            try {
                driver.releaseBuffer(input);
            } catch (RuntimeException failure) {
                original.addSuppressed(failure);
                released = false;
            }
        }
        if (output != null) {
            try {
                driver.releaseBuffer(output);
            } catch (RuntimeException failure) {
                original.addSuppressed(failure);
                released = false;
            }
        }
        return released;
    }

    private Lease reserve(
            long resident,
            long pinned,
            int events,
            int queues) {
        synchronized (this) {
            if (resident < 0
                    || pinned < 0
                    || events <= 0
                    || queues <= 0
                    || resident > limits.residentBytes() - residentBytes
                    || pinned > limits.pinnedHostBytes() - pinnedHostBytes
                    || events > limits.outstandingEvents() - outstandingEvents
                    || queues > limits.queueSlots() - queueSlots) {
                throw new IllegalStateException("compute provider quota exhausted");
            }
            residentBytes += resident;
            pinnedHostBytes += pinned;
            outstandingEvents += events;
            queueSlots += queues;
            return new Lease(this, resident, pinned, events, queues);
        }
    }

    private void release(Lease lease) {
        synchronized (this) {
            residentBytes -= lease.resident;
            pinnedHostBytes -= lease.pinned;
            outstandingEvents -= lease.events;
            queueSlots -= lease.queues;
            if (residentBytes < 0
                    || pinnedHostBytes < 0
                    || outstandingEvents < 0
                    || queueSlots < 0) {
                throw new IllegalStateException("compute provider quota accounting underflow");
            }
        }
    }

    private void requireOwner(Operation operation) {
        if (operation.owner != this) {
            throw new IllegalArgumentException("foreign compute provider operation");
        }
    }

    private static boolean terminal(OperationState state) {
        return switch (state) {
            case SUCCEEDED, FAILED, CANCELLED, DEVICE_LOST, RELEASE_FAILED -> true;
            default -> false;
        };
    }

    private static long checkedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("compute provider quota arithmetic overflow", overflow);
        }
    }

    private static final class Lease {
        private final TrustedOpenClProvider owner;
        private final long resident;
        private final long pinned;
        private final int events;
        private final int queues;
        private boolean released;

        private Lease(
                TrustedOpenClProvider owner,
                long resident,
                long pinned,
                int events,
                int queues) {
            this.owner = owner;
            this.resident = resident;
            this.pinned = pinned;
            this.events = events;
            this.queues = queues;
        }

        void release() {
            synchronized (this) {
                if (released) {
                    throw new IllegalStateException("compute provider quota lease already released");
                }
                owner.release(this);
                released = true;
            }
        }
    }
}

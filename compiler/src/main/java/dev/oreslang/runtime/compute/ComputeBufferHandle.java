package dev.oreslang.runtime.compute;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime-owned device allocation authority. Source-level Buffer<T> is affine. */
public final class ComputeBufferHandle<T> {
    public enum State { IDLE, IN_FLIGHT, POISONED, RETIRED }

    private static final AtomicLong NEXT_LOCK_RANK = new AtomicLong(1);

    private final ComputeDeviceHandle device;
    private final long allocationGeneration;
    private final long byteLength;
    private final long alignment;
    private final String typeTag;
    private final String ownerDomain;
    private final long lockRank;

    private State state = State.IDLE;
    private long sequence;
    private boolean backendQuiescent = true;
    private boolean submitted;
    private boolean paired;
    private Object activeProof;
    private Object activePairProof;
    private ComputeFenceHandle.Direction activeDirection;

    ComputeBufferHandle(
            ComputeDeviceHandle device,
            long allocationGeneration,
            long byteLength,
            long alignment,
            String typeTag,
            String ownerDomain) {
        this.device = Objects.requireNonNull(device, "device");
        this.typeTag = requireText(typeTag, "typeTag");
        this.ownerDomain = requireText(ownerDomain, "ownerDomain");
        if (allocationGeneration <= 0) {
            throw new IllegalArgumentException("allocationGeneration must be positive");
        }
        if (byteLength <= 0) {
            throw new IllegalArgumentException("byteLength must be positive");
        }
        if (alignment <= 0 || (alignment & (alignment - 1)) != 0) {
            throw new IllegalArgumentException("alignment must be a positive power of two");
        }
        this.allocationGeneration = allocationGeneration;
        this.byteLength = byteLength;
        this.alignment = alignment;
        this.lockRank = nextLockRank();
    }

    public synchronized State state() { return state; }
    public long allocationGeneration() { return allocationGeneration; }
    public long byteLength() { return byteLength; }
    public long alignment() { return alignment; }
    public String typeTag() { return typeTag; }
    public String ownerDomain() { return ownerDomain; }
    public ComputeDeviceHandle device() { return device; }

    synchronized ComputeFenceHandle beginTransfer(
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long offset,
            long length,
            ComputeFenceHandle.Direction direction) {
        Pending pending = prepareLocked(
                expectedDevice, expectedOwnerDomain, offset, length, direction);
        commitLocked(pending, null);
        return pending.fence();
    }

    synchronized void markSubmitted(ComputeFenceHandle fence) {
        validateActiveFence(fence);
        if (submitted) {
            throw new IllegalStateException("compute transfer already submitted");
        }
        submitted = true;
    }

    synchronized void abortBeforeEnqueue(ComputeFenceHandle fence) {
        validateActiveFence(fence);
        if (submitted) {
            throw new IllegalStateException(
                    "submitted compute transfer cannot use pre-enqueue abort");
        }
        resetIdleLocked();
    }

    synchronized void complete(ComputeFenceHandle fence, boolean succeeded) {
        validateSubmittedFence(fence);
        state = succeeded ? State.IDLE : State.POISONED;
        backendQuiescent = true;
        submitted = false;
        clearActiveLocked();
    }

    synchronized void cancel(ComputeFenceHandle fence) {
        validateSubmittedFence(fence);
        state = State.POISONED;
        // The backend may still be touching the allocation.
    }

    synchronized void acknowledgeCancelled(ComputeFenceHandle fence) {
        if (paired) {
            throw new IllegalStateException(
                    "paired compute cancellation requires atomic pair acknowledgement");
        }
        if (state != State.POISONED || backendQuiescent || !submitted) {
            throw new IllegalStateException("cancelled transfer is not awaiting quiescence");
        }
        validateReceipt(fence);
        backendQuiescent = true;
        submitted = false;
        clearActiveLocked();
    }

    synchronized void retire(
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain) {
        if (!device.sameAuthority(expectedDevice)
                || !ownerDomain.equals(expectedOwnerDomain)) {
            throw new IllegalArgumentException("foreign compute retirement authority");
        }
        if (state == State.RETIRED) {
            throw new IllegalStateException("compute allocation already retired");
        }
        if (paired || state == State.IN_FLIGHT || !backendQuiescent) {
            throw new IllegalStateException("compute allocation is not quiescent");
        }
        state = State.RETIRED;
        clearActiveLocked();
    }

    static ComputeFencePair beginKernelPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long length) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input == output) {
            throw new IllegalArgumentException("kernel input/output allocations must differ");
        }
        return withPairLocks(input, output, () -> {
            if (length <= 0
                    || input.byteLength != length
                    || output.byteLength != length) {
                throw new IllegalArgumentException(
                        "kernel pair must cover both exact allocations");
            }
            Object pairProof = new Object();
            Pending in = input.prepareLocked(
                    expectedDevice,
                    expectedOwnerDomain,
                    0,
                    length,
                    ComputeFenceHandle.Direction.KERNEL_INPUT);
            Pending out = output.prepareLocked(
                    expectedDevice,
                    expectedOwnerDomain,
                    0,
                    length,
                    ComputeFenceHandle.Direction.KERNEL_OUTPUT);
            ComputeFencePair pair =
                    new ComputeFencePair(in.fence(), out.fence(), pairProof);
            input.commitLocked(in, pairProof);
            output.commitLocked(out, pairProof);
            return pair;
        });
    }

    static void markPairSubmitted(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        withPairLocks(input, output, () -> {
            input.validatePairFenceLocked(pair.input(), pair.pairProof());
            output.validatePairFenceLocked(pair.output(), pair.pairProof());
            if (input.submitted || output.submitted) {
                throw new IllegalStateException("compute fence pair already submitted");
            }
            input.submitted = true;
            output.submitted = true;
            return null;
        });
    }

    static void abortPairBeforeEnqueue(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        withPairLocks(input, output, () -> {
            input.validatePairFenceLocked(pair.input(), pair.pairProof());
            output.validatePairFenceLocked(pair.output(), pair.pairProof());
            if (input.submitted || output.submitted) {
                throw new IllegalStateException(
                        "submitted compute pair cannot use pre-enqueue abort");
            }
            input.resetIdleLocked();
            output.resetIdleLocked();
            return null;
        });
    }

    static void completePair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair,
            boolean succeeded) {
        withPairLocks(input, output, () -> {
            input.validateSubmittedPairFenceLocked(pair.input(), pair.pairProof());
            output.validateSubmittedPairFenceLocked(pair.output(), pair.pairProof());
            State terminal = succeeded ? State.IDLE : State.POISONED;
            input.state = terminal;
            output.state = terminal;
            input.backendQuiescent = true;
            output.backendQuiescent = true;
            input.submitted = false;
            output.submitted = false;
            input.clearActiveLocked();
            output.clearActiveLocked();
            return null;
        });
    }

    static void cancelPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        withPairLocks(input, output, () -> {
            input.validateSubmittedPairFenceLocked(pair.input(), pair.pairProof());
            output.validateSubmittedPairFenceLocked(pair.output(), pair.pairProof());
            input.state = State.POISONED;
            output.state = State.POISONED;
            return null;
        });
    }

    static void acknowledgeCancelledPair(
            ComputeBufferHandle<?> input,
            ComputeBufferHandle<?> output,
            ComputeFencePair pair) {
        withPairLocks(input, output, () -> {
            input.validateCancelledPairFenceLocked(pair.input(), pair.pairProof());
            output.validateCancelledPairFenceLocked(pair.output(), pair.pairProof());
            input.backendQuiescent = true;
            output.backendQuiescent = true;
            input.submitted = false;
            output.submitted = false;
            input.clearActiveLocked();
            output.clearActiveLocked();
            return null;
        });
    }

    private Pending prepareLocked(
            ComputeDeviceHandle expectedDevice,
            String expectedOwnerDomain,
            long offset,
            long length,
            ComputeFenceHandle.Direction direction) {
        Objects.requireNonNull(expectedDevice, "expectedDevice");
        Objects.requireNonNull(expectedOwnerDomain, "expectedOwnerDomain");
        Objects.requireNonNull(direction, "direction");
        if (!device.sameAuthority(expectedDevice)) {
            throw new IllegalArgumentException("foreign compute device authority");
        }
        if (!ownerDomain.equals(expectedOwnerDomain)) {
            throw new IllegalArgumentException("foreign compute owner domain");
        }
        if (state != State.IDLE || !backendQuiescent || paired) {
            throw new IllegalStateException("compute buffer is not idle and quiescent");
        }
        if (offset < 0 || length <= 0 || offset > byteLength - length) {
            throw new IllegalArgumentException("transfer range outside allocation");
        }
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalStateException("compute fence sequence exhausted");
        }

        Object proof = new Object();
        long next = sequence + 1;
        ComputeFenceHandle fence = new ComputeFenceHandle(
                device,
                allocationGeneration,
                next,
                direction,
                offset,
                length,
                proof);
        return new Pending(fence, next, proof, direction);
    }

    private void commitLocked(Pending pending, Object pairProof) {
        sequence = pending.nextSequence();
        activeProof = pending.proof();
        activeDirection = pending.direction();
        activePairProof = pairProof;
        paired = pairProof != null;
        backendQuiescent = false;
        submitted = false;
        state = State.IN_FLIGHT;
    }

    private void validateActiveFence(ComputeFenceHandle fence) {
        if (paired) {
            throw new IllegalStateException(
                    "paired compute operation requires atomic pair transition");
        }
        if (state != State.IN_FLIGHT) {
            throw new IllegalStateException("compute buffer has no in-flight transfer");
        }
        validateReceipt(fence);
    }

    private void validateSubmittedFence(ComputeFenceHandle fence) {
        validateActiveFence(fence);
        if (!submitted) {
            throw new IllegalStateException("compute transfer has not been submitted");
        }
    }

    private void validatePairFenceLocked(
            ComputeFenceHandle fence,
            Object pairProof) {
        if (!paired
                || state != State.IN_FLIGHT
                || activePairProof == null
                || activePairProof != pairProof) {
            throw new IllegalStateException("stale or foreign compute fence pair");
        }
        validateReceipt(fence);
    }

    private void validateSubmittedPairFenceLocked(
            ComputeFenceHandle fence,
            Object pairProof) {
        validatePairFenceLocked(fence, pairProof);
        if (!submitted) {
            throw new IllegalStateException("compute fence pair has not been submitted");
        }
    }

    private void validateCancelledPairFenceLocked(
            ComputeFenceHandle fence,
            Object pairProof) {
        if (!paired
                || state != State.POISONED
                || backendQuiescent
                || !submitted
                || activePairProof == null
                || activePairProof != pairProof) {
            throw new IllegalStateException(
                    "cancelled compute pair is not awaiting quiescence");
        }
        validateReceipt(fence);
    }

    private void validateReceipt(ComputeFenceHandle fence) {
        Objects.requireNonNull(fence, "fence");
        if (!fence.matchesDevice(device)
                || fence.allocationGeneration() != allocationGeneration
                || fence.sequence() != sequence
                || fence.direction() != activeDirection
                || fence.proof() == null
                || fence.proof() != activeProof) {
            throw new IllegalStateException("stale or foreign compute fence");
        }
    }

    private void resetIdleLocked() {
        state = State.IDLE;
        backendQuiescent = true;
        submitted = false;
        clearActiveLocked();
    }

    private void clearActiveLocked() {
        activeProof = null;
        activePairProof = null;
        activeDirection = null;
        paired = false;
    }

    private static long nextLockRank() {
        long rank = NEXT_LOCK_RANK.getAndIncrement();
        if (rank <= 0) {
            throw new IllegalStateException("compute allocation lock rank exhausted");
        }
        return rank;
    }

    private static <R> R withPairLocks(
            ComputeBufferHandle<?> left,
            ComputeBufferHandle<?> right,
            PairAction<R> action) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(action, "action");
        if (left == right) {
            throw new IllegalArgumentException("compute pair allocations must differ");
        }
        ComputeBufferHandle<?> first =
                left.lockRank < right.lockRank ? left : right;
        ComputeBufferHandle<?> second = first == left ? right : left;
        synchronized (first) {
            synchronized (second) {
                return action.run();
            }
        }
    }

    private record Pending(
            ComputeFenceHandle fence,
            long nextSequence,
            Object proof,
            ComputeFenceHandle.Direction direction) {}

    @FunctionalInterface
    private interface PairAction<R> {
        R run();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

package dev.oreslang.runtime.compute;

import java.util.Objects;

/** Runtime-owned device allocation authority. Source-level Buffer<T> is affine. */
public final class ComputeBufferHandle<T> {
    public enum State { IDLE, IN_FLIGHT, POISONED, RETIRED }

    private final ComputeDeviceHandle device;
    private final long allocationGeneration;
    private final long byteLength;
    private final long alignment;
    private final String typeTag;
    private final String ownerDomain;

    private State state = State.IDLE;
    private long sequence;
    private boolean backendQuiescent = true;
    private Object activeProof;
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
        Objects.requireNonNull(expectedDevice, "expectedDevice");
        Objects.requireNonNull(expectedOwnerDomain, "expectedOwnerDomain");
        Objects.requireNonNull(direction, "direction");
        if (!device.sameAuthority(expectedDevice)) {
            throw new IllegalArgumentException("foreign compute device authority");
        }
        if (!ownerDomain.equals(expectedOwnerDomain)) {
            throw new IllegalArgumentException("foreign compute owner domain");
        }
        if (state != State.IDLE || !backendQuiescent) {
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
                device, allocationGeneration, next, direction, offset, length, proof);
        sequence = next;
        activeProof = proof;
        activeDirection = direction;
        backendQuiescent = false;
        state = State.IN_FLIGHT;
        return fence;
    }

    synchronized void complete(ComputeFenceHandle fence, boolean succeeded) {
        validateActiveFence(fence);
        state = succeeded ? State.IDLE : State.POISONED;
        backendQuiescent = true;
        activeProof = null;
        activeDirection = null;
    }

    synchronized void cancel(ComputeFenceHandle fence) {
        validateActiveFence(fence);
        state = State.POISONED;
        // The backend may still be touching the allocation.
    }

    synchronized void acknowledgeCancelled(ComputeFenceHandle fence) {
        if (state != State.POISONED || backendQuiescent) {
            throw new IllegalStateException("cancelled transfer is not awaiting quiescence");
        }
        validateReceipt(fence);
        backendQuiescent = true;
        activeProof = null;
        activeDirection = null;
    }

    synchronized void retire(ComputeDeviceHandle expectedDevice, String expectedOwnerDomain) {
        if (!device.sameAuthority(expectedDevice) || !ownerDomain.equals(expectedOwnerDomain)) {
            throw new IllegalArgumentException("foreign compute retirement authority");
        }
        if (state == State.IN_FLIGHT || !backendQuiescent) {
            throw new IllegalStateException("compute allocation is not quiescent");
        }
        state = State.RETIRED;
        activeProof = null;
        activeDirection = null;
    }

    private void validateActiveFence(ComputeFenceHandle fence) {
        if (state != State.IN_FLIGHT) {
            throw new IllegalStateException("compute buffer has no in-flight transfer");
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

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}

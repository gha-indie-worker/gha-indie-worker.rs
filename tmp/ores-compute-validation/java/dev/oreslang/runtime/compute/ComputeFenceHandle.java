package dev.oreslang.runtime.compute;

/** Unforgeable runtime transfer receipt. Cancellation is not completion. */
public final class ComputeFenceHandle {
    public enum Direction { UPLOAD, DOWNLOAD, KERNEL_INPUT, KERNEL_OUTPUT }

    private final ComputeDeviceHandle device;
    private final long allocationGeneration;
    private final long sequence;
    private final Direction direction;
    private final long offset;
    private final long length;
    private final Object proof;

    ComputeFenceHandle(
            ComputeDeviceHandle device,
            long allocationGeneration,
            long sequence,
            Direction direction,
            long offset,
            long length,
            Object proof) {
        this.device = device;
        this.allocationGeneration = allocationGeneration;
        this.sequence = sequence;
        this.direction = direction;
        this.offset = offset;
        this.length = length;
        this.proof = proof;
    }

    public long allocationGeneration() { return allocationGeneration; }
    public long sequence() { return sequence; }
    public Direction direction() { return direction; }
    public long offset() { return offset; }
    public long length() { return length; }

    boolean matchesDevice(ComputeDeviceHandle candidate) { return device.sameAuthority(candidate); }
    Object proof() { return proof; }
}

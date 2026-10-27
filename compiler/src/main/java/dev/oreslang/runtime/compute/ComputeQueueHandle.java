package dev.oreslang.runtime.compute;

import java.util.Objects;

/**
 * Runtime-owned queue capability bound to one opaque device authority and
 * nonzero queue generation. Textual queue identifiers are not authority.
 */
public final class ComputeQueueHandle {
    private final ComputeDeviceHandle device;
    private final long generation;
    private final Object proof;

    ComputeQueueHandle(ComputeDeviceHandle device, long generation) {
        this.device = Objects.requireNonNull(device, "device");
        if (generation <= 0) {
            throw new IllegalArgumentException("queue generation must be positive");
        }
        this.generation = generation;
        this.proof = new Object();
    }

    public long generation() { return generation; }

    ComputeDeviceHandle deviceAuthority() { return device; }

    boolean sameAuthority(ComputeQueueHandle other) {
        return other != null && proof == other.proof;
    }
}

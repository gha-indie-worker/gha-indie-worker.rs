package dev.oreslang.runtime.accel;

import java.util.Objects;

/**
 * Opaque device-buffer ownership/fence bookkeeping for future GPU providers.
 * A descriptor is NOT device allocation; only an external trusted backend can
 * allocate/copy/dispatch. No raw GPU pointer or host fallback is exposed.
 */
public final class GpuBufferFence implements AutoCloseable {
    public enum Status { IDLE, IN_FLIGHT, POISONED, CLOSED }
    public record Identity(String tenant, String device, long allocationGeneration) {
        public Identity {
            if (tenant == null || tenant.isBlank() || device == null || device.isBlank()
                    || allocationGeneration < 0) throw new IllegalArgumentException("invalid device identity");
        }
    }

    /**
     * Unforgeable, per-submission completion capability, not just two
     * guessable integers. Keep the constructor inaccessible to guest code.
     * Its object identity must match the precise in-flight submission.
     */
    public static final class Ticket {
        private final long generation;
        private final long sequence;
        private Ticket(long generation, long sequence) {
            this.generation = generation;
            this.sequence = sequence;
        }
        public long generation() { return generation; }
        public long sequence() { return sequence; }
    }

    private static final class State {
        final Identity identity;
        final long bytes;
        Status status = Status.IDLE;
        boolean backendQuiescent = true;
        long sequence;
        Ticket activeTicket;
        State(Identity identity, long bytes) {
            this.identity = Objects.requireNonNull(identity);
            if (bytes <= 0) throw new IllegalArgumentException("device byte size must be positive");
            this.bytes = bytes;
        }
    }

    private final State state;
    private boolean moved;

    public GpuBufferFence(Identity identity, long bytes) { this(new State(identity, bytes)); }
    private GpuBufferFence(State state) { this.state = state; }

    private void active() {
        if (moved || state.status == Status.CLOSED) {
            throw new IllegalStateException("device ownership moved or buffer closed");
        }
    }
    public synchronized Identity identity() { active(); return state.identity; }
    public synchronized long byteLength() { active(); return state.bytes; }
    public synchronized Status status() { active(); return state.status; }

    public synchronized GpuBufferFence take() {
        active();
        if (state.status != Status.IDLE) {
            throw new IllegalStateException("cannot transfer device buffer with pending/failed fence");
        }
        moved = true;
        return new GpuBufferFence(state);
    }

    synchronized Ticket beginCommand(Identity caller) {
        active();
        if (!state.identity.equals(caller)) throw new SecurityException("foreign device/tenant/generation");
        if (state.status != Status.IDLE) throw new IllegalStateException("device buffer not idle");
        if (state.sequence == Long.MAX_VALUE) throw new IllegalStateException("device fence sequence overflow");
        state.backendQuiescent = false;
        state.status = Status.IN_FLIGHT;
        state.activeTicket = new Ticket(state.identity.allocationGeneration(), ++state.sequence);
        return state.activeTicket;
    }

    /*
     * These five package-private state-transition methods are trusted runtime
     * implementation details. They MUST NOT be exposed via Oreslang guest
     * interop or a public Java-facing API: a ticket alone cannot attest that
     * the GPU command actually finished. A real provider must separately
     * prove native device-event completion and handle release.
     */
    /** Called ONLY after the device reports completion for this exact ticket. */
    synchronized void complete(Ticket ticket, boolean success) {
        active();
        if (state.status != Status.IN_FLIGHT || ticket != state.activeTicket) {
            throw new IllegalStateException("stale or mismatched device event");
        }
        state.status = success ? Status.IDLE : Status.POISONED;
        state.backendQuiescent = true;
        state.activeTicket = null;
    }

    /** Cancel requires device synchronization; do not reuse or free prematurely. */
    synchronized void cancelPending(Ticket ticket) {
        active();
        if (state.status != Status.IN_FLIGHT || ticket != state.activeTicket) {
            throw new IllegalStateException("stale cancellation");
        }
        state.status = Status.POISONED;
    }

    /** A cancelled kernel may still be running until its device fence completes. */
    synchronized void acknowledgeCancelledDeviceEvent(Ticket ticket, Identity caller) {
        active();
        if (!state.identity.equals(caller) || ticket != state.activeTicket
                || state.status != Status.POISONED || state.backendQuiescent) {
            throw new IllegalStateException("invalid cancelled-device completion");
        }
        state.backendQuiescent = true;
        state.activeTicket = null;
    }

    /** After backend confirms device quiescence and releases the opaque handle. */
    synchronized void retireAfterBackendRelease(Identity caller) {
        active();
        if (!state.identity.equals(caller)) throw new SecurityException("foreign device/tenant/generation");
        if (state.status == Status.IN_FLIGHT || !state.backendQuiescent)
            throw new IllegalStateException("device command still in flight or awaiting cancelled fence");
        state.status = Status.CLOSED;
    }

    @Override public synchronized void close() {
        if (moved) return; // No ownership remains on the transferred wrapper.
        if (state.status != Status.CLOSED) {
            throw new IllegalStateException("backend must quiesce and release before owner closes");
        }
    }
}

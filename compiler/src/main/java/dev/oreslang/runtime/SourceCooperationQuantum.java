package dev.oreslang.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Soft, per-dispatch loop quantum for a single heap-backed Oreslang source task.
 *
 * <p>One cheap counter increment and mask per completed backedge; monotonic
 * clock reads occur only after an amortized batch. This class decides when a
 * checkpoint is due, NOT where suspension is legal. Only SourceTask's proven
 * post-commit continuation can publish OresScheduler.cooperate().</p>
 *
 * <p>The logical task survives carrier migration: the time since its last
 * cooperation is intentionally distinct from the current dispatch's quantum.
 * A long pending await starts a fresh soft quantum on resumption but does not
 * count as a cooperation or run the task's lexical cleanup.</p>
 */
public final class SourceCooperationQuantum {
    public static final int LOOP_CHECK_INTERVAL = 64;
    public static final int MAX_BACKEDGES_PER_DISPATCH = 512;
    public static final long SOFT_DISPATCH_QUANTUM_NANOS = 2_000_000L;

    private final LongSupplier monotonicNanos;
    private long lastCooperateAtNanos;
    private long dispatchStartedAtNanos;
    private long backedgesThisDispatch;

    /** Production clock, injected in tests to validate exact clock-read cost. */
    public SourceCooperationQuantum() {
        this(System::nanoTime);
    }

    public SourceCooperationQuantum(LongSupplier monotonicNanos) {
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        lastCooperateAtNanos = monotonicNanos.getAsLong();
        dispatchStartedAtNanos = lastCooperateAtNanos;
    }

    /** Refresh only the fairness quantum; keep the logical cooperation clock. */
    public void beginDispatch() {
        dispatchStartedAtNanos = monotonicNanos.getAsLong();
        backedgesThisDispatch = 0;
    }

    /**
     * Called at the compiler-lowered, committed loop backedge.
     * Absolutely no clock reads or scheduler calls on the usual fast path.
     */
    public boolean completedBackedgeNeedsCheck() {
        // Once a no-yield region has consumed the soft reduction budget,
        // poll EVERY subsequent committed backedge. Otherwise a guard that
        // closes between ordinary 64-backedge polls could delay an already
        // overdue handoff, and a wrapped signed counter could erase the cap.
        // Saturate at Long.MAX_VALUE rather than wrapping to a negative value.
        long completed = backedgesThisDispatch;
        if (completed != Long.MAX_VALUE) {
            completed = ++backedgesThisDispatch;
        }
        return completed >= MAX_BACKEDGES_PER_DISPATCH
                || (completed & (LOOP_CHECK_INTERVAL - 1)) == 0;
    }

    /** Slow path only: reduction cap, watchdog request, or active dispatch time.
     * The watchdog cannot force suspension: the caller must already own a
     * post-commit, heap-resumable continuation and be outside no-yield scopes.
     */
    public boolean shouldCooperateAtCheck() {
        return shouldCooperateAtCheck(false);
    }

    public boolean shouldCooperateAtCheck(boolean watchdogRequested) {
        // A pending watchdog notice is advisory and must not cause another
        // clock read. Only the compiler-lowered caller may publish Cooperate.
        if (watchdogRequested || backedgesThisDispatch >= MAX_BACKEDGES_PER_DISPATCH) {
            return true;
        }
        return monotonicNanos.getAsLong() - dispatchStartedAtNanos
                >= SOFT_DISPATCH_QUANTUM_NANOS;
    }

    /** Called only for an actual rt cooperate / automatic scheduler handoff. */
    public void recordedCooperate() {
        lastCooperateAtNanos = monotonicNanos.getAsLong();
    }

    /**
     * Scheduling metric, not generator yield count/time.
     * Its sole call-site should be deliberate diagnostics or a slow path.
     */
    public long timeSinceLastCooperateNanos() {
        return Math.max(0L, monotonicNanos.getAsLong() - lastCooperateAtNanos);
    }

    public long completedBackedgesThisDispatch() {
        return backedgesThisDispatch;
    }
}

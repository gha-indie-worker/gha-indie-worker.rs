package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Independent observer of running scheduler/actor turns.
 *
 * <p>A scheduler carrier cannot reliably diagnose starvation when every carrier
 * is busy. This daemon therefore runs outside the guest dispatcher pools.
 * It may ONLY request cooperation and record an overrun: it never interrupts,
 * suspends, cancels, or re-enters a guest turn. Compiler-lowered resumable
 * safepoints own physical handoff.</p>
 *
 * <p>Leases have unique object identities (not recycled thread or actor ids).
 * Retirement synchronizes with inspection so a retired turn cannot generate
 * a late notice about a replacement turn on a reused carrier.</p>
 */
final class ActorTurnWatchdog {
    static final long OVERRUN_NANOS = TimeUnit.MILLISECONDS.toNanos(4);
    private static final Set<Lease> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final AtomicLong OBSERVER_FAILURES = new AtomicLong();
    private static final ScheduledExecutorService WATCHDOG;

    static {
        ThreadFactory factory = runnable -> Thread.ofPlatform()
                .daemon(true)
                .name("ores-turn-watchdog")
                .unstarted(runnable);
        WATCHDOG = Executors.newSingleThreadScheduledExecutor(factory);
        WATCHDOG.scheduleWithFixedDelay(
                ActorTurnWatchdog::scanSafely, 2, 2, TimeUnit.MILLISECONDS);
    }

    private ActorTurnWatchdog() { }

    static Lease enter(Consumer<Lease> onOverrun) {
        Lease lease = new Lease(Objects.requireNonNull(onOverrun, "onOverrun"));
        ACTIVE.add(lease);
        return lease;
    }

    /** Failed observer callbacks and recoverable monitor errors, never guest exceptions. */
    static long observerFailures() {
        return OBSERVER_FAILURES.get();
    }

    /** A bad observer must never permanently suppress all future scans. */
    private static void scanSafely() {
        try {
            scanAt(System.nanoTime());
        } catch (RuntimeException | AssertionError failure) {
            OBSERVER_FAILURES.incrementAndGet();
        }
    }

    // Package-private for deterministic regression tests; does not run guest work.
    static void scanAt(long nowNanos) {
        for (Lease lease : ACTIVE) {
            try {
                lease.inspect(nowNanos);
            } catch (RuntimeException | AssertionError failure) {
                // The failing lease remains requested and cannot fire twice.
                // Another actor's lease must still be inspected this tick.
                OBSERVER_FAILURES.incrementAndGet();
            }
        }
    }

    static final class Lease implements AutoCloseable {
        private final long startedNanos = System.nanoTime();
        private final Consumer<Lease> onOverrun;
        private final AtomicBoolean requested = new AtomicBoolean();
        private volatile long lastCheckpointNanos = startedNanos;
        private boolean active = true;

        private Lease(Consumer<Lease> onOverrun) {
            this.onOverrun = onOverrun;
        }

        long elapsedNanos(long nowNanos) {
            return Math.max(0L, nowNanos - startedNanos);
        }

        long sinceCheckpointNanos(long nowNanos) {
            return Math.max(0L, nowNanos - lastCheckpointNanos);
        }

        void checkpoint() {
            lastCheckpointNanos = System.nanoTime();
        }

        boolean preemptionRequested() {
            return requested.get();
        }

        private synchronized void inspect(long nowNanos) {
            if (!active || requested.get()
                    || elapsedNanos(nowNanos) < OVERRUN_NANOS) return;
            requested.set(true);
            // The callback is VM-internal, nonblocking accounting only.
            // It must never invoke arbitrary guest code on the monitor thread.
            onOverrun.accept(this);
        }

        @Override
        public synchronized void close() {
            active = false;
            ACTIVE.remove(this);
        }
    }
}

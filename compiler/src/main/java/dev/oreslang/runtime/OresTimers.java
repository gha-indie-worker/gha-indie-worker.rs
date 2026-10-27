package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Objects;
import java.util.HashSet;
import java.util.concurrent.Semaphore;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime-only monotonic timer source for compiler-lowered sleep/after/interval.
 *
 * <p>Expiration settles an OresFuture; it NEVER executes guest code. An
 * OresScheduler await resumes on the original scheduler's next dispatch.
 * Owners must explicitly close this helper until OresVM lifecycle wiring
 * exists. Close revokes registrations but is NOT a barrier for already-running
 * clock callbacks or guest-frame quiescence. Actor heap reclamation requires a
 * separate scheduler/continuation retirement acknowledgement.</p>
 */
public final class OresTimers implements AutoCloseable {
    private static final int MAX_ACTIVE_PER_OWNER = 4096;
    private static final int MAX_INTERVALS_PER_OWNER = 4096;
    // Per-context limits alone are insufficient when an untrusted application
    // creates thousands of timer owners against the shared host clock.
    static final int MAX_GLOBAL_ACTIVE_TIMERS = 16384;
    // Callback timers are queued as scheduler tasks only; actor mailbox
    // admission remains a separate, unimplemented source-level integration.
    private static final ThreadLocal<Boolean> IN_CLOCK_THREAD =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Semaphore GLOBAL_SLOTS =
            new Semaphore(MAX_GLOBAL_ACTIVE_TIMERS);
    private static final Semaphore GLOBAL_CALLBACK_SLOTS =
            new Semaphore(MAX_GLOBAL_ACTIVE_TIMERS);

    static int availableGlobalCallbackSlots() {
        return GLOBAL_CALLBACK_SLOTS.availablePermits();
    }

    static int availableGlobalTimerSlots() {
        return GLOBAL_SLOTS.availablePermits();
    }
    private static final ScheduledThreadPoolExecutor CLOCK = createClock();

    private static ScheduledThreadPoolExecutor createClock() {
        AtomicInteger ids = new AtomicInteger();
        ScheduledThreadPoolExecutor clock = new ScheduledThreadPoolExecutor(2,
                runnable -> Thread.ofPlatform().daemon(true)
                        .name("ores-monotonic-timer-" + ids.incrementAndGet())
                        .unstarted(runnable));
        clock.setRemoveOnCancelPolicy(true);
        return clock;
    }

    private final Object admission = new Object();
    private final Set<OresFuture<Void>> active = new HashSet<>();
    private final Set<Interval> intervals = new HashSet<>();
    private final Set<TimerHandle> callbackTimers = new HashSet<>();
    private volatile boolean closed;

    /**
     * Complete after the duration measured on a monotonic clock.
     * Cancellation removes the scheduled host callback, not an actor turn.
     */
    public OresFuture<Void> afterNanos(long delayNanos) {
        if (delayNanos < 0) throw new IllegalArgumentException("negative timer delay");
        AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        OresFuture<Void> future = new OresFuture<>(() -> {
            ScheduledFuture<?> event = scheduled.get();
            if (event != null) event.cancel(false);
        });
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("timer owner closed");
            if (active.size() >= MAX_ACTIVE_PER_OWNER) {
                throw new RejectedExecutionException("timer owner admission is full");
            }
            if (!GLOBAL_SLOTS.tryAcquire()) {
                throw new RejectedExecutionException("global timer admission is full");
            }
            // Register cleanup BEFORE arming the timer. A zero-delay
            // expiration is allowed to run before schedule() returns.
            boolean registeredCleanup = false;
            try {
                future.whenCompleteRuntime((ignored, failure) -> {
                    // Never retain completed/cancelled timers or their slots.
                    synchronized (admission) {
                        active.remove(future);
                    }
                    GLOBAL_SLOTS.release();
                });
                registeredCleanup = true;
                active.add(future);
                scheduled.set(CLOCK.schedule(
                        () -> {
                            IN_CLOCK_THREAD.set(Boolean.TRUE);
                            try {
                                future.completeFromRuntime(null);
                            } finally {
                                IN_CLOCK_THREAD.remove();
                            }
                        },
                        delayNanos, TimeUnit.NANOSECONDS));
            } catch (RuntimeException | Error failure) {
                // No Future escapes before this block exits. Settle it to
                // run the same exact-once cleanup on failed admission.
                if (registeredCleanup) {
                    future.failFromRuntime(failure);
                } else {
                    GLOBAL_SLOTS.release();
                }
                throw failure;
            }
        }
        return future;
    }

    public OresFuture<Void> sleepNanos(long delayNanos) {
        return afterNanos(delayNanos);
    }

    /**
     * Host-runtime one-shot callback substrate. This is NOT a guest-language
     * time.set_timer builtin and cannot bypass an actor's mailbox/CONTROL lane.
     * The passed scheduler must be the actual owner of the callback's lifetime.
     * In particular, actor callers may not use a generic scheduler here until
     * actor-local source callback admission and lexical capture checks exist.
     */
    public TimerHandle setTimerNanos(long delayNanos, OresScheduler scheduler,
                                    Runnable callback) {
        if (delayNanos < 0) throw new IllegalArgumentException("negative timer delay");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(callback, "callback");
        // Until actor-local CONTROL admission exists, do not turn a generic
        // scheduler callback into a back door around actor single-writer turns.
        if (ActorRuntime.inActorExecution() || ActorRuntime.inActorGroupMailmanExecution()) {
            throw new IllegalStateException(
                    "actor timer callbacks require actor-owned CONTROL admission");
        }
        final TimerHandle handle = new TimerHandle(scheduler, callback);
        final OresFuture<Void> deadline;
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("timer owner closed");
            if (callbackTimers.size() >= MAX_ACTIVE_PER_OWNER) {
                throw new RejectedExecutionException("timer callback owner admission is full");
            }
            if (!GLOBAL_CALLBACK_SLOTS.tryAcquire()) {
                throw new RejectedExecutionException("global callback admission is full");
            }
            try {
                callbackTimers.add(handle);
                // Reentrant owner admission lock: afterNanos acquires the same
                // monitor and registers the monotonic deadline atomically
                // against concurrent owner close.
                deadline = afterNanos(delayNanos);
                handle.deadline = deadline;
            } catch (RuntimeException | Error failure) {
                callbackTimers.remove(handle);
                GLOBAL_CALLBACK_SLOTS.release();
                throw failure;
            }
        }
        deadline.whenCompleteRuntime((value, failure) -> handle.onDeadline(failure));
        return handle;
    }

    public final class TimerHandle {
        private static final int ARMED = 0;
        private static final int READY = 1;
        private static final int RUNNING = 2;
        private static final int DONE = 3;
        private static final int CANCELLED = 4;

        private final OresScheduler scheduler;
        private final Runnable callback;
        private final AtomicInteger state = new AtomicInteger(ARMED);
        private final AtomicReference<OresFuture<Void>> queued = new AtomicReference<>();
        private final OresFuture<Void> completion;
        private volatile OresFuture<Void> deadline;

        private TimerHandle(OresScheduler scheduler, Runnable callback) {
            this.scheduler = scheduler;
            this.callback = callback;
            // Admit cancellation by atomically revoking callback execution
            // authority, BEFORE publishing cancelled Future state.
            this.completion = new OresFuture<>(() -> {
                for (;;) {
                    final int observed = state.get();
                    if (observed != ARMED && observed != READY) return false;
                    if (state.compareAndSet(observed, CANCELLED)) return true;
                }
            }, () -> {
                OresFuture<Void> timer = deadline;
                if (timer != null) timer.cancel(false);
                OresFuture<Void> job = queued.get();
                if (job != null) job.cancel(false);
            });
            completion.whenCompleteRuntime((value, failure) -> {
                synchronized (admission) {
                    callbackTimers.remove(this);
                }
                GLOBAL_CALLBACK_SLOTS.release();
            });
        }

        /** True only if cancellation won before callback invocation began. */
        public boolean cancel() {
            return completion.cancel(false);
        }

        /** Settles on callback success/failure, or a pre-start cancellation. */
        public OresFuture<Void> getDone() {
            return completion;
        }

        /** Read-only state observation; does not authorize callback execution. */
        public boolean isDone() {
            return completion.isDone();
        }

        private void onDeadline(Throwable failure) {
            if (failure != null) {
                if (state.compareAndSet(ARMED, DONE)) {
                    completion.failFromRuntime(OresFuture.unwrap(failure));
                }
                return;
            }
            if (!state.compareAndSet(ARMED, READY)) return;

            final OresFuture<Void> job;
            try {
                job = scheduler.startSync(() -> {
                    // This check happens on the owning scheduler's dispatch,
                    // after any competing cancellation or owner teardown.
                    if (!state.compareAndSet(READY, RUNNING)) return null;
                    try {
                        // An inline executor must NEVER turn the monotonic
                        // clock thread into a guest execution thread.
                        if (Boolean.TRUE.equals(IN_CLOCK_THREAD.get())) {
                            throw new IllegalStateException(
                                "clock thread cannot execute a timer callback");
                        }
                        callback.run();
                        state.set(DONE);
                        completion.completeFromRuntime(null);
                    } catch (RuntimeException | Error callbackFailure) {
                        state.set(DONE);
                        completion.failFromRuntime(callbackFailure);
                        throw callbackFailure;
                    }
                    return null;
                });
            } catch (RuntimeException | Error submitFailure) {
                if (state.compareAndSet(READY, DONE)) {
                    completion.failFromRuntime(submitFailure);
                }
                return;
            }
            queued.set(job);
            if (state.get() == CANCELLED) job.cancel(false);
            // The scheduler may close before the queued callback starts.
            // Never claim a RUNNING callback has physically quiesced just
            // because the scheduler's outward Future was cancelled.
            job.whenCompleteRuntime((ignored, jobFailure) -> {
                if (state.compareAndSet(READY, DONE)) {
                    if (jobFailure != null) {
                        completion.failFromRuntime(OresFuture.unwrap(jobFailure));
                    } else {
                        completion.failFromRuntime(new IllegalStateException(
                            "timer callback scheduler settled without executing callback"));
                    }
                }
            });
        }
    }

    /** Demand-driven fixed-delay interval: only next() arms a timer. */
    public Interval intervalNanos(long periodNanos) {
        if (periodNanos <= 0) {
            throw new IllegalArgumentException("interval period must be positive");
        }
        synchronized (admission) {
            if (closed) throw new RejectedExecutionException("timer owner closed");
            if (intervals.size() >= MAX_INTERVALS_PER_OWNER) {
                throw new RejectedExecutionException("interval owner admission is full");
            }
            Interval interval = new Interval(periodNanos);
            intervals.add(interval);
            return interval;
        }
    }

    public final class Interval implements AutoCloseable {
        private final long periodNanos;
        private long delivered;
        private boolean cancelled;
        private OresFuture<Long> pending;

        private Interval(long periodNanos) {
            this.periodNanos = periodNanos;
        }

        /**
         * Wait for the next tick. Exactly one outstanding next() is permitted;
         * intervals neither pile up missed ticks nor execute guest callbacks.
         */
        public synchronized OresFuture<Long> next() {
            if (cancelled || closed) return cancelledNext();
            if (pending != null) {
                throw new IllegalStateException("interval already has an awaiting consumer");
            }
            final OresFuture<Void> wake;
            try {
                wake = afterNanos(periodNanos);
            } catch (RejectedExecutionException rejected) {
                // Owner close racing with next() is a cancellation, not a
                // surprise synchronous exception from the interval API.
                if (closed) {
                    cancel();
                    return cancelledNext();
                }
                throw rejected;
            }
            OresFuture<Long> result = new OresFuture<>(() -> wake.cancel(false));
            pending = result;
            result.whenCompleteRuntime((value, error) -> {
                synchronized (Interval.this) {
                    if (pending == result) pending = null;
                }
            });
            wake.whenCompleteRuntime((ignored, failure) -> {
                synchronized (Interval.this) {
                    if (cancelled || result.isDone()) {
                        result.cancel(false);
                    } else if (failure instanceof CancellationException) {
                        result.cancel(false);
                    } else if (failure != null) {
                        result.failFromRuntime(OresFuture.unwrap(failure));
                    } else {
                        long nextIndex = delivered + 1;
                        if (nextIndex <= 0) {
                            result.failFromRuntime(new ArithmeticException(
                                    "interval tick counter overflow"));
                        } else if (result.completeFromRuntime(nextIndex)) {
                            delivered = nextIndex;
                        }
                    }
                }
            });
            return result;
        }

        private OresFuture<Long> cancelledNext() {
            OresFuture<Long> result = new OresFuture<>();
            result.cancel(false);
            return result;
        }

        /** Return true only for the first successful interval cancellation. */
        public synchronized boolean cancel() {
            if (cancelled) return false;
            cancelled = true;
            if (pending != null) pending.cancel(false);
            synchronized (admission) {
                intervals.remove(this);
            }
            return true;
        }

        @Override
        public void close() {
            cancel();
        }

        public synchronized long delivered() {
            return delivered;
        }
    }

    @Override
    public void close() {
        final ArrayList<OresFuture<Void>> toCancel;
        final ArrayList<Interval> intervalsToClose;
        final ArrayList<TimerHandle> callbacksToClose;
        synchronized (admission) {
            if (closed) return;
            closed = true;
            toCancel = new ArrayList<>(active);
            intervalsToClose = new ArrayList<>(intervals);
            callbacksToClose = new ArrayList<>(callbackTimers);
        }
        // No admission lock may be held while cancellation notifies waiters.
        for (TimerHandle callback : callbacksToClose) callback.cancel();
        for (Interval interval : intervalsToClose) interval.cancel();
        for (OresFuture<Void> timer : toCancel) timer.cancel(false);
    }

    int activeCallbackCount() {
        synchronized (admission) {
            return callbackTimers.size();
        }
    }

    int activeIntervalCount() {
        synchronized (admission) {
            return intervals.size();
        }
    }

    int activeTimerCount() {
        synchronized (admission) {
            return active.size();
        }
    }
}

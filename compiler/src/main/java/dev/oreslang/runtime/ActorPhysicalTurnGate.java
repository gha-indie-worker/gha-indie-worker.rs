package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Optional per-runtime, cross-actor-domain physical turn admission.
 *
 * <p>When full, an ActorCell's dispatch sits in this bounded logical queue:
 * it does NOT take an OS worker or park an Oreslang Task. A permit covers the
 * dispatched Runnable through physical completion, including its TurnExecutor
 * boundary. This is not a global budget across multiple ActorRuntimes and
 * does not yet include independently owned OresScheduler Task dispatches.</p>
 */
final class ActorPhysicalTurnGate {
    private static final class Pending {
        final Runnable task;
        final Consumer<Runnable> submit;
        final Consumer<Throwable> reject;
        // Zero-cost-per-turn state: pending=0, running=1, rejected=2.
        // Synchronization on the existing Pending object linearizes guest
        // entry versus a submitter which enqueued work but then threw.
        private int claim;

        Pending(Runnable task, Consumer<Runnable> submit, Consumer<Throwable> reject) {
            this.task = task;
            this.submit = submit;
            this.reject = reject;
        }
    }

    private final int maximum;
    private final int queuedLimit;
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private int admitted;
    private int peak;
    private boolean closed;

    ActorPhysicalTurnGate(int maximum, int queuedLimit) {
        if (maximum < 1 || queuedLimit < 1) {
            throw new IllegalArgumentException("invalid physical actor turn budget");
        }
        this.maximum = maximum;
        this.queuedLimit = queuedLimit;
    }

    int maximum() { return maximum; }
    synchronized int peak() { return peak; }
    synchronized int queued() { return pending.size(); }

    void dispatch(Runnable task, Consumer<Runnable> submit, Consumer<Throwable> reject) {
        Pending item = new Pending(
                Objects.requireNonNull(task), Objects.requireNonNull(submit),
                Objects.requireNonNull(reject));
        synchronized (this) {
            if (closed) throw new RejectedExecutionException("actor turn gate closed");
            if (admitted >= maximum) {
                if (pending.size() >= queuedLimit) {
                    throw new RejectedExecutionException("actor turn gate backlog exhausted");
                }
                pending.addLast(item);
                return;
            }
            admitted++;
            peak = Math.max(peak, admitted);
        }
        launch(item);
    }

    private void launch(Pending first) {
        // Executor admission callbacks must ENQUEUE, never run a guest turn
        // synchronously. Iterative failure draining prevents recursive
        // stack growth if thousands of queued dispatches encounter shutdown.
        Pending item = first;
        for (;;) {
            try {
                Pending accepted = item;
                item.submit.accept(() -> {
                    boolean cancelledOnClose;
                    synchronized (accepted) {
                        if (accepted.claim != 0) {
                            // A submitter rejected this runnable after enqueueing.
                            // The rejection already transferred its permit.
                            return;
                        }
                        // Serialize the decision to BEGIN a guest turn with
                        // close(). A dispatch handed to a stalled executor
                        // must not start guest code after the gate has closed.
                        synchronized (ActorPhysicalTurnGate.this) {
                            cancelledOnClose = closed;
                            accepted.claim = cancelledOnClose ? 2 : 1;
                        }
                    }
                    if (cancelledOnClose) {
                        try {
                            accepted.reject.accept(new RejectedExecutionException(
                                    "actor turn gate closed before dispatch started"));
                        } catch (Throwable ignored) {
                            // Revocation must not strand the transferred permit.
                        } finally {
                            release();
                        }
                        return;
                    }
                    try {
                        accepted.task.run();
                    } finally {
                        release();
                    }
                });
                return;
            } catch (RuntimeException | Error failure) {
                synchronized (item) {
                    if (item.claim == 1) {
                        // An inline or already-running turn owns completion;
                        // no duplicate rejection or physical release.
                        throw failure;
                    }
                    if (item.claim != 0) {
                        throw new IllegalStateException(
                                "actor dispatch admitted or rejected twice", failure);
                    }
                    item.claim = 2;
                }
                try {
                    item.reject.accept(failure);
                } catch (Throwable observerFailure) {
                    // A broken lifecycle observer cannot retain the permit.
                    if (observerFailure != failure) {
                        failure.addSuppressed(observerFailure);
                    }
                }
                synchronized (this) {
                    if (closed || pending.isEmpty()) {
                        admitted--;
                        return;
                    }
                    // Transfer the permit to the next bounded FIFO successor.
                    item = pending.removeFirst();
                }
            }
        }
    }

    private void release() {
        Pending next;
        synchronized (this) {
            if (admitted == 0) throw new IllegalStateException("actor turn gate underflow");
            if (closed || pending.isEmpty()) {
                admitted--;
                return;
            }
            // Transfer the existing permit. No other admission can overtake
            // this FIFO successor, and callbacks execute outside the lock.
            next = pending.removeFirst();
        }
        launch(next);
    }

    /** Called only after ActorRuntime has stopped its owned ActorCells. */
    void close() {
        List<Pending> rejected;
        synchronized (this) {
            if (closed) return;
            closed = true;
            rejected = new ArrayList<>(pending);
            pending.clear();
        }
        for (Pending item : rejected) {
            try {
                item.reject.accept(new RejectedExecutionException("actor turn gate closed"));
            } catch (Throwable ignored) {
                // One broken dispatch failure observer cannot retain or
                // prevent revocation of subsequent queued actor admissions.
            }
        }
    }
}

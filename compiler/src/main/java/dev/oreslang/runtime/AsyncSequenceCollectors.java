package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler-owned, bounded asynchronous sequence terminal operations.
 *
 * <p>Never drives guest continuations on producer or channel-completion
 * threads. The supplied Cursor is consumed exclusively and closed AFTER the
 * underlying Task has physically retired. Cancellation of the collection
 * does not cancel the borrowed producer. This is a host-runtime foundation,
 * not yet an Oreslang source-visible Stream or Observable intrinsic.</p>
 */
public final class AsyncSequenceCollectors {
    /** Strict item bound independent of the source's buffering configuration. */
    public static final int MAX_COLLECT_ITEMS = 65_536;

    private AsyncSequenceCollectors() { }

    public static final class LimitExceededException extends IllegalStateException {
        LimitExceededException(int maxItems) {
            super("async sequence exceeds maximum collection size " + maxItems);
        }
    }

    /**
     * Read-only result plus physical-retirement barrier, with cancellation
     * authority restricted to the Task this collection actually started.
     *
     * <p>result() may settle before release of cursor resources; await
     * getDone() before reclaiming externally scoped buffers or arenas.</p>
     */
    public static final class CollectionHandle<T> {
        private final TaskHandle<List<T>> task;
        private final OresFuture<TaskHandle.TaskOutcome<List<T>>> done =
                new OresFuture<>(() -> false, () -> { });
        private final AtomicBoolean closed = new AtomicBoolean();

        private CollectionHandle(TaskHandle<List<T>> task,
                AsyncSequences.Cursor<T> cursor) {
            this.task = Objects.requireNonNull(task, "task");
            Objects.requireNonNull(cursor, "cursor");
            task.getDone().whenCompleteRuntime((outcome, failure) -> {
                // The underlying Task has already physically retired, and no
                // Task resume can issue another nextAsync against this cursor.
                // Do not close a shared Channel: borrowed cursor.close() only
                // removes the cursor's own waiter.
                Throwable closeFailure = null;
                if (closed.compareAndSet(false, true)) {
                    try {
                        cursor.close();
                    } catch (Throwable thrown) {
                        closeFailure = thrown;
                    }
                }

                if (failure != null) {
                    if (closeFailure != null && closeFailure != failure) {
                        failure.addSuppressed(closeFailure);
                    }
                    done.failFromRuntime(failure);
                } else if (closeFailure != null) {
                    done.failFromRuntime(closeFailure);
                } else {
                    done.completeFromRuntime(outcome);
                }
            });
        }

        public OresFuture<List<T>> result() {
            return task.result();
        }

        /** Task is physically retired AND cursor cleanup is complete. */
        public OresFuture<TaskHandle.TaskOutcome<List<T>>> getDone() {
            return done;
        }

        public boolean cancel(String reason) {
            return task.cancel(reason);
        }

        public boolean cancel() {
            return task.cancel();
        }

        public boolean isCancellationRequested() {
            return task.isCancellationRequested();
        }
    }

    /**
     * Collect an entire finite sequence into a strictly bounded List.
     *
     * <p>One additional event past maxItems is consumed to prove the input
     * exceeded the limit. This operation takes exclusive consumption of the
     * cursor, closes it when its Task is physically finished, but does not
     * revoke a borrowed Channel or Future producer. An infinite sequence
     * deterministically fails after maxItems+1 items instead of accumulating
     * unbounded retained values.</p>
     *
     * <p>The limit is an ITEM count, not a byte budget. Production source
     * profiles require a separate allocation quota before granting
     * Oreslang guest access to a materializing collector.</p>
     */
    public static <T> CollectionHandle<T> collect(
            OresScheduler scheduler, AsyncSequences.Cursor<T> cursor,
            int maxItems) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(cursor, "cursor");
        if (maxItems < 0 || maxItems > MAX_COLLECT_ITEMS) {
            throw new IllegalArgumentException(
                    "maxItems must be between 0 and " + MAX_COLLECT_ITEMS);
        }

        // Explicit state machine: a completed await does NOT call nextAsync
        // recursively on its producer's completion thread. Each item is
        // processed only in a scheduler-owned resume turn.
        OresScheduler.Task<List<T>> collector = new OresScheduler.Task<>() {
            private final ArrayList<T> received = new ArrayList<>();
            private boolean started;
            private boolean terminal;

            @Override
            public OresScheduler.Step<List<T>> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (terminal) {
                    throw new IllegalStateException("collection resumed after terminal");
                }

                OresScheduler.cancellationCheckpoint();
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "collection started with a noninitial continuation");
                    }
                    started = true;
                } else {
                    if (resume.initial()) {
                        throw new IllegalStateException(
                                "collection resumed with duplicate initial event");
                    }
                    if (resume.failure() != null) {
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        if (failure instanceof Exception exception) throw exception;
                        if (failure instanceof Error error) throw error;
                        throw new IllegalStateException(
                                "nonexceptional Throwable in stream continuation", failure);
                    }
                    if (!(resume.value() instanceof Optional<?> next)) {
                        throw new IllegalStateException(
                                "stream nextAsync returned a non-Optional value");
                    }
                    if (next.isEmpty()) {
                        terminal = true;
                        return OresScheduler.done(List.copyOf(received));
                    }
                    if (received.size() >= maxItems) {
                        terminal = true;
                        throw new LimitExceededException(maxItems);
                    }
                    @SuppressWarnings("unchecked")
                    T value = (T) next.orElseThrow();
                    received.add(value);
                }
                return OresScheduler.await(cursor.nextAsync());
            }
        };

        final TaskHandle<List<T>> task;
        try {
            task = scheduler.startTracked(collector);
        } catch (RuntimeException | Error admissionFailure) {
            try {
                cursor.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != admissionFailure) {
                    admissionFailure.addSuppressed(cleanupFailure);
                }
            }
            throw admissionFailure;
        }
        return new CollectionHandle<>(task, cursor);
    }
}

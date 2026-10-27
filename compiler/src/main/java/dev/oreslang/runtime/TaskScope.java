package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/**
 * Explicit host/runtime lifetime owner for scheduled Tasks.
 *
 * <p>Closing the scope requests cancellation and does not itself join
 * running children. Cancellation hooks and Future subscribers may execute
 * synchronously, so a bounded nonparking cancellation latency is NOT promised.
 * {@link #getDone()} is an observation-only Future and
 * settles only after the scope is sealed and every admitted TaskHandle has
 * reported physical scheduler quiescence. This class does not expose guest
 * actor/tenant cancellation rights or a source-language TaskScope type.</p>
 */
public final class TaskScope implements AutoCloseable {
    private final OresScheduler scheduler;
    private final TaskCancellation cancellation = new TaskCancellation();
    private final Object admissionLock = new Object();
    private final OresFuture<Void> done = new OresFuture<>(() -> false, () -> { });
    private final OresFuture<List<Throwable>> cleanupFailures =
            new OresFuture<>(() -> false, () -> { });
    private final ArrayList<Throwable> releaseErrors = new ArrayList<>();
    private boolean sealed;
    private int active;
    // Observation retained through physical scope quiescence so source-level
    // structured concurrency can surface an unawaited child failure without
    // changing getDone() from a pure quiescence witness.
    private TaskHandle.TaskOutcome<?> firstNonSuccess;

    TaskScope(OresScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Atomically reserve a child slot or reject a sealed scope. */
    public <T> TaskHandle<T> startTask(OresScheduler.Task<T> task) {
        return startTask(task, () -> { });
    }

    /**
     * Runtime-only child admission with an exact physical-quiescence callback.
     * The callback runs after TaskHandle.getDone() settles and before this
     * scope retires the child's active slot. A callback failure is retained as
     * a structured child failure and cannot make the scope appear successful.
     */
    <T> TaskHandle<T> startTask(
            OresScheduler.Task<T> task,
            Runnable onPhysicalQuiescence) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onPhysicalQuiescence, "onPhysicalQuiescence");
        synchronized (admissionLock) {
            if (sealed) {
                throw new RejectedExecutionException("TaskScope is sealed");
            }
            // Reservation linearizes against seal/cancel. Keep the monitor
            // free of scheduler dispatch: runtime-owned schedulers may run a
            // complete guest turn synchronously during TaskHandle.start().
            // Never wrap the signed join-slot counter: a negative count
            // could break the quiescence witness and scope accounting.
            if (active == Integer.MAX_VALUE) {
                throw new RejectedExecutionException("TaskScope child count exhausted");
            }
            active++;
        }
        final TaskHandle<T> child;
        try {
            child = TaskHandle.start(scheduler, task, cancellation);
        } catch (RuntimeException | Error admissionFailure) {
            // The reservation is already visible to concurrent seal/cancel;
            // undo it under the same lock, then publish completion outside.
            final boolean publish;
            synchronized (admissionLock) {
                active--;
                publish = sealed && active == 0;
            }
            if (publish) publishCompletion();
            throw admissionFailure;
        }
        // The child may have finished before callback registration. In that
        // case, OresFuture invokes subscribers synchronously; never deliver
        // arbitrary scope completion observers while holding admissionLock.
        // Any seal/cancel racing this interval sees active > 0 and waits for
        // childDone() after its actual scheduler quiescence.
        child.getDone().whenCompleteRuntime(
                (outcome, failure) -> {
                    Throwable completionFailure = failure;
                    try {
                        onPhysicalQuiescence.run();
                    } catch (RuntimeException | Error retirementFailure) {
                        if (completionFailure == null) {
                            completionFailure = retirementFailure;
                        } else if (completionFailure != retirementFailure) {
                            completionFailure.addSuppressed(retirementFailure);
                        }
                    }
                    List<Throwable> failedReleases;
                    try {
                        failedReleases = child.cleanupFailures().join();
                    } catch (RuntimeException cleanupObservationFailure) {
                        failedReleases = List.of(cleanupObservationFailure);
                    }
                    childDone(outcome, completionFailure, failedReleases);
                });
        return child;
    }

    private void childDone(
            TaskHandle.TaskOutcome<?> outcome,
            Throwable observationFailure,
            List<Throwable> failedReleases) {
        boolean publish;
        synchronized (admissionLock) {
            if (active <= 0) {
                throw new IllegalStateException("TaskScope child accounting underflow");
            }
            releaseErrors.addAll(failedReleases);
            if (firstNonSuccess == null) {
                if (observationFailure != null) {
                    firstNonSuccess =
                            new TaskHandle.Failed<>(observationFailure);
                } else if (outcome != null
                        && !(outcome instanceof TaskHandle.Success<?>)) {
                    firstNonSuccess = outcome;
                }
            }
            active--;
            publish = sealed && active == 0;
        }
        // Never deliver arbitrary Future subscribers under the admission lock.
        if (publish) publishCompletion();
    }

    private void publishCompletion() {
        final List<Throwable> snapshot;
        synchronized (admissionLock) {
            if (!sealed || active != 0) return;
            snapshot = List.copyOf(releaseErrors);
        }
        cleanupFailures.completeFromRuntime(snapshot);
        if (snapshot.isEmpty()) done.completeFromRuntime(null);
        else done.failFromRuntime(new TaskOwnedLeases.CleanupFailure(snapshot));
    }

    /** Stop admitting new children but let existing children complete normally. */
    public void seal() {
        boolean publish;
        synchronized (admissionLock) {
            sealed = true;
            publish = active == 0;
        }
        if (publish) publishCompletion();
    }

    /** Cancel owned children and seal admission. Does not wait for execution. */
    public boolean cancel(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank() || reason.length() > 256) {
            throw new IllegalArgumentException("reason must contain 1..256 nonblank characters");
        }
        synchronized (admissionLock) {
            sealed = true;
        }
        try {
            return cancellation.cancel(reason);
        } finally {
            boolean publish;
            synchronized (admissionLock) {
                publish = active == 0;
            }
            if (publish) publishCompletion();
        }
    }

    public boolean isCancellationRequested() {
        return cancellation.isCancelled();
    }

    /** Runtime diagnostics: completed child tokens must not be retained. */
    int liveCancellationChildren() {
        return cancellation.ownedChildCount();
    }

    /**
     * True quiescence witness for the currently admitted children, once the
     * scope is sealed. Callers should await it rather than blocking carriers.
     */
    public OresFuture<Void> getDone() {
        return done;
    }

    /** Child logical-lease release failures, fixed before scope retirement. */
    public OresFuture<List<Throwable>> cleanupFailures() {
        return cleanupFailures;
    }

    /**
     * First failed/cancelled child observed by this scope, if any.
     *
     * <p>Callers using this for source structured-concurrency semantics should
     * inspect it only after {@link #getDone()} has settled. It carries no
     * cancellation or retirement authority.</p>
     */
    public TaskHandle.TaskOutcome<?> firstNonSuccessOutcome() {
        synchronized (admissionLock) {
            return firstNonSuccess;
        }
    }

    /** Requests cancellation without joining; notification hooks may still take time. */
    @Override
    public void close() {
        cancel("scope closed");
    }
}

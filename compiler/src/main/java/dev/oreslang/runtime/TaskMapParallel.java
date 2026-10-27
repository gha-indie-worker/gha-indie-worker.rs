package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Strictly bounded structured host-side map-parallel composition.
 *
 * <p>The caller supplies lazy factory functions producing compiler-lowered
 * Tasks, NOT eagerly launched TaskHandles or already-running Futures.
 * A single coordinator Task admits at most concurrency TaskScope children;
 * slots are recycled only after each child's getDone() reports physical
 * quiescence. All user callbacks run through OresScheduler execution turns,
 * never on a producer notification thread or a dedicated Rx thread pool.</p>
 *
 * <p>Host runtime staging: this does not yet expose Oreslang source
 * Task.map_parallel and does not prove guest type/effect/ownership safety.</p>
 */
public final class TaskMapParallel {
    private static final int MAX_INPUTS = 65_536;
    private static final int MAX_ADMISSIONS_PER_TURN = 32;

    private TaskMapParallel() { }

    private record Running<R>(int index, TaskHandle<R> handle) { }

    /**
     * Indexed child diagnostic. True cancellation comes only from an
     * authenticated TaskHandle.Cancelled outcome; a user-thrown
     * CancellationException remains an ordinary failure.
     */
    public record ChildFailure(int index, Throwable cause, boolean cancelled) {
        public ChildFailure {
            if (index < 0) throw new IllegalArgumentException("negative child index");
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * The task's result may settle before all children are physically retired
     * on error/cancellation. getDone() is a separate scope+coordinator
     * physical-quiescence barrier; cancelling it cannot forge completion.
     */
    public static final class MapHandle<R> {
        private final TaskHandle<List<R>> coordinator;
        private final TaskScope scope;
        private final OresFuture<Void> done = new OresFuture<>(() -> false, () -> { });
        private final OresFuture<List<ChildFailure>> failures =
                new OresFuture<>(() -> false, () -> { });
        private final AtomicBoolean published = new AtomicBoolean();
        private final TaskHandle<?>[] admitted;

        private MapHandle(TaskHandle<List<R>> coordinator, TaskScope scope,
                          TaskHandle<?>[] admitted) {
            this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.admitted = Objects.requireNonNull(admitted, "admitted");

            coordinator.result().whenCompleteRuntime((value, failure) -> {
                try {
                    if (failure == null) {
                        scope.seal();
                    } else {
                        scope.cancel("map_parallel coordinator failed or cancelled");
                    }
                } finally {
                    publishIfQuiescent();
                }
            });
            coordinator.getDone().whenCompleteRuntime(
                    (outcome, failure) -> publishIfQuiescent());
            scope.getDone().whenCompleteRuntime(
                    (ignored, failure) -> publishIfQuiescent());
            publishIfQuiescent();
        }

        private void publishIfQuiescent() {
            if (!coordinator.getDone().isDone() || !scope.getDone().isDone()) {
                return;
            }
            if (!published.compareAndSet(false, true)) return;

            // Join only already-retired child outcomes. No guest callback is
            // run here and no scheduler carrier is parked. Capture secondary
            // genuine failures as well as sibling cancellations, in input
            // order, without MUTATING or WRAPPING the primary Throwable.
            ArrayList<ChildFailure> diagnostic = new ArrayList<>();
            for (int index = 0; index < admitted.length; index++) {
                TaskHandle<?> child = admitted[index];
                if (child == null) continue; // never admitted
                admitted[index] = null;       // do not retain retired arenas
                TaskHandle.TaskOutcome<?> outcome = child.getDone().join();
                if (outcome instanceof TaskHandle.Failed<?> failed) {
                    diagnostic.add(new ChildFailure(index, failed.failure(), false));
                } else if (outcome instanceof TaskHandle.Cancelled<?> cancelled) {
                    diagnostic.add(new ChildFailure(index,
                            new java.util.concurrent.CancellationException(
                                    cancelled.reason()), true));
                }
            }
            failures.completeFromRuntime(List.copyOf(diagnostic));
            // A physical join is not a CLEAN release when any owned child
            // failed to unlock its logical lease. Scope.getDone() now fails
            // closed in that case. Do not turn it back into success here.
            try {
                scope.getDone().join(); // already terminal, never parks a carrier
                done.completeFromRuntime(null);
            } catch (java.util.concurrent.CompletionException
                    | java.util.concurrent.CancellationException compromised) {
                done.failFromRuntime(OresFuture.unwrap(compromised));
            }
        }

        public OresFuture<List<R>> result() {
            return coordinator.result();
        }

        /** Neither the parent nor its owned children are physically active. */
        public OresFuture<Void> getDone() {
            return done;
        }

        /**
         * After physical retirement, report ALL admitted child failures
         * and cancellations in input order. Secondary errors are not lost
         * behind the fail-fast caller-facing result. Observation-only Future.
         */
        public OresFuture<List<ChildFailure>> childFailures() {
            return failures;
        }

        public boolean cancel(String reason) {
            boolean cancelled = coordinator.cancel(reason);
            if (cancelled) scope.cancel(reason);
            return cancelled;
        }

        public boolean cancel() {
            return cancel("map_parallel cancelled");
        }
    }

    /**
     * Run an ordered mapping of at most 65536 items with 1..maxConcurrent
     * physically active children. Factory creation runs during a coordinator
     * scheduler turn and should only CONSTRUCT a lazy OresScheduler.Task<R>;
     * its body executes when its scope admits/schedules that child.
     *
     * <p>Output order is INPUT order even if children finish out of order.
     * On first observed child failure/cancellation, stop admitting, cancel
     * siblings and retain their memory permits until physical retirement.
     * An authenticated cancelled child requests cancellation of the
     * coordinator; an unauthenticated user-thrown CancellationException
     * stays a failure. childFailures() preserves secondary outcomes after
     * physical retirement. Source-level effect signatures remain separate.</p>
     */
    public static <T, R> MapHandle<R> mapLimit(
            OresScheduler scheduler, List<T> inputs, int maxConcurrent,
            Function<? super T, ? extends OresScheduler.Task<R>> factory) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(inputs, "inputs");
        // Bound before copying: otherwise an oversized malicious caller can
        // force a large retained input snapshot merely to be rejected.
        if (inputs.size() > MAX_INPUTS) {
            throw new IllegalArgumentException(
                    "map_parallel input size exceeds " + MAX_INPUTS);
        }
        if (maxConcurrent < 1 || maxConcurrent > MAX_INPUTS) {
            throw new IllegalArgumentException(
                    "maxConcurrent must be between 1 and " + MAX_INPUTS);
        }
        List<T> items = List.copyOf(inputs);
        TaskScope scope = scheduler.newTaskScope();
        TaskHandle<?>[] admittedHandles = new TaskHandle<?>[items.size()];
        AtomicReference<TaskHandle<List<R>>> coordinatorOwner = new AtomicReference<>();
        OresFuture<Void> ownerReady = new OresFuture<>();

        OresScheduler.Task<List<R>> driver = new OresScheduler.Task<>() {
            private final Object[] ordered = new Object[items.size()];
            private final ArrayList<Running<R>> active = new ArrayList<>();
            private int nextIndex;
            private int completed;
            private boolean started;
            private boolean ownerReadyObserved;

            private void inspectRetiredChildren() throws Exception {
                Throwable genuineFailure = null;
                int firstFailureIndex = Integer.MAX_VALUE;
                String authenticatedCancellation = null;
                for (int i = active.size() - 1; i >= 0; i--) {
                    Running<R> running = active.get(i);
                    OresFuture<TaskHandle.TaskOutcome<R>> physical =
                            running.handle().getDone();
                    if (!physical.isDone()) continue;
                    TaskHandle.TaskOutcome<R> outcome = physical.join();
                    active.remove(i);
                    if (outcome instanceof TaskHandle.Success<?> success) {
                        ordered[running.index()] = success.value();
                        completed++;
                    } else if (outcome instanceof TaskHandle.Failed<?> failed) {
                        // Preserve the original cause, and prefer a genuine
                        // failure over a sibling cancelled during fail-fast.
                        // For simultaneously retired errors, use input index
                        // for deterministic primary-error selection.
                        if (running.index() < firstFailureIndex) {
                            firstFailureIndex = running.index();
                            genuineFailure = failed.failure();
                        }
                    } else if (outcome instanceof TaskHandle.Cancelled<?> cancelled) {
                        authenticatedCancellation = cancelled.reason();
                    } else {
                        throw new IllegalStateException("unrecognized TaskOutcome");
                    }
                }
                if (genuineFailure != null) throwOriginal(genuineFailure);
                if (authenticatedCancellation != null) {
                    TaskHandle<List<R>> authority = coordinatorOwner.get();
                    if (authority == null) {
                        throw new IllegalStateException("map coordinator has no owner");
                    }
                    authority.cancel("map_parallel child cancelled");
                    // A runtime-authenticated checkpoint makes this a true
                    // TaskHandle.Cancelled, NOT a Failed(CancellationException).
                    OresScheduler.cancellationCheckpoint();
                    throw new IllegalStateException("cancellation checkpoint missed");
                }
            }

            private OresScheduler.Step<List<R>> dispatch() throws Exception {
                OresScheduler.cancellationCheckpoint();
                inspectRetiredChildren();
                int admitted = 0;
                while (nextIndex < items.size() && active.size() < maxConcurrent) {
                    if (admitted++ == MAX_ADMISSIONS_PER_TURN) {
                        // Preserve cooperative fairness on a large fan-out.
                        return OresScheduler.cooperate();
                    }
                    int at = nextIndex++;
                    OresScheduler.Task<R> task = Objects.requireNonNull(
                            factory.apply(items.get(at)), "map factory returned null Task");
                    TaskHandle<R> child = scope.startTask(task);
                    admittedHandles[at] = child;
                    active.add(new Running<>(at, child));
                }

                if (completed == items.size()) {
                    ArrayList<R> result = new ArrayList<>(ordered.length);
                    for (Object raw : ordered) {
                        @SuppressWarnings("unchecked")
                        R item = (R) raw;
                        result.add(Objects.requireNonNull(item,
                                "map_parallel child produced null; use Option<T>"));
                    }
                    return OresScheduler.done(List.copyOf(result));
                }

                if (active.isEmpty()) {
                    throw new IllegalStateException("map_parallel lost all live children");
                }

                ArrayList<OresFuture<? extends TaskHandle.TaskOutcome<R>>> pending =
                        new ArrayList<>(active.size());
                for (Running<R> running : active) {
                    pending.add(running.handle().getDone());
                }
                return OresScheduler.await(FutureCombinators.race(pending));
            }

            @Override
            public OresScheduler.Step<List<R>> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "map_parallel started with noninitial resume");
                    }
                    started = true;
                    // No child factory may execute before its TaskScope owner,
                    // error observers and parent cancellation authority exist.
                    return OresScheduler.await(ownerReady);
                }
                if (resume.initial()) {
                    throw new IllegalStateException(
                            "map_parallel resumed with duplicate initial resume");
                }
                if (!ownerReadyObserved) {
                    ownerReadyObserved = true;
                    if (resume.failure() != null) {
                        throwOriginal(OresFuture.unwrap(resume.failure()));
                    }
                    return dispatch();
                }
                if (resume.failure() != null) {
                    throwOriginal(OresFuture.unwrap(resume.failure()));
                }
                return dispatch();
            }
        };

        final TaskHandle<List<R>> coordinator;
        try {
            coordinator = scheduler.startTracked(driver);
        } catch (RuntimeException | Error rejected) {
            try {
                scope.cancel("map_parallel coordinator admission rejected");
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != rejected) rejected.addSuppressed(cleanup);
            }
            throw rejected;
        }
        MapHandle<R> operation = new MapHandle<>(coordinator, scope, admittedHandles);
        coordinatorOwner.set(coordinator);
        ownerReady.completeFromRuntime(null);
        return operation;
    }

    /** Direct sequential mapping uses the same physical retirement semantics. */
    public static <T, R> MapHandle<R> mapSeries(
            OresScheduler scheduler, List<T> inputs,
            Function<? super T, ? extends OresScheduler.Task<R>> factory) {
        return mapLimit(scheduler, inputs, 1, factory);
    }

    private static void throwOriginal(Throwable failure) throws Exception {
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("nonexceptional Throwable in child Task", failure);
    }
}

package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scheduler-owned lowering target for Future-aware value pipelines.
 *
 * <p>One OresScheduler task executes all stages in order. An ordinary result
 * stays in the same task turn; a Future result suspends the task, rather than
 * spawning another callback task per pipeline stage. This host/runtime API
 * is not the source-language Task.pipeline syntax or a typed-effect checker:
 * the compiler must prove port types, effects and actor ownership before
 * admitting a source pipeline here.</p>
 */
public final class TaskPipeline {
    private TaskPipeline() { }

    /**
     * The scheduler's own await graph tracks *internal* Task Futures. A
     * pipeline exposes a distinct bridge Future, so cycles A -> B -> A through
     * those bridges require a second bounded, identity-based dependency map.
     * Only currently pending awaits are retained; no global completed Task
     * or actor graph is kept here.
     */
    private static final Object PIPELINE_GRAPH_LOCK = new Object();
    private static final IdentityHashMap<OresFuture<?>, OresFuture<?>> PIPELINE_AWAITS =
            new IdentityHashMap<>();

    private static void armPipelineAwait(OresFuture<?> owner, OresFuture<?> awaited) {
        if (owner == null) return; // inline first dispatch before result publication
        synchronized (PIPELINE_GRAPH_LOCK) {
            OresFuture<?> cursor = awaited;
            int remaining = PIPELINE_AWAITS.size() + 1;
            while (cursor != null && remaining-- > 0) {
                if (cursor == owner) {
                    throw new IllegalStateException(
                            "cyclic pipeline await dependency detected");
                }
                cursor = PIPELINE_AWAITS.get(cursor);
            }
            if (cursor != null) {
                throw new IllegalStateException("existing cyclic pipeline await graph");
            }
            if (PIPELINE_AWAITS.putIfAbsent(owner, awaited) != null) {
                throw new IllegalStateException("pipeline already has a pending await dependency");
            }
        }
    }

    private static void retirePipelineAwait(OresFuture<?> owner, OresFuture<?> expected) {
        if (owner == null) return;
        synchronized (PIPELINE_GRAPH_LOCK) {
            if (expected == null || PIPELINE_AWAITS.get(owner) == expected) {
                PIPELINE_AWAITS.remove(owner);
            }
        }
    }

    static int pendingPipelineAwaitCount() {
        synchronized (PIPELINE_GRAPH_LOCK) {
            return PIPELINE_AWAITS.size();
        }
    }

    /**
     * Result settlement and verified physical Task retirement are different
     * observations. getDone() proves this pipeline Task has left all scheduler
     * turns and its tracked child scope is retired; an independently owned
     * stage Future may require its own physical retirement receipt.
     */
    public static final class PipelineHandle<T> {
        private final OresFuture<T> result;
        private final TaskHandle<T> task;

        private PipelineHandle(OresFuture<T> result, TaskHandle<T> task) {
            this.result = Objects.requireNonNull(result, "result");
            this.task = Objects.requireNonNull(task, "task");
        }

        public OresFuture<T> result() {
            return result;
        }

        public OresFuture<TaskHandle.TaskOutcome<T>> getDone() {
            return task.getDone();
        }

        public boolean cancel() {
            return result.cancel(false);
        }
    }

    @FunctionalInterface
    public interface Stage {
        /** Called on the pipeline's scheduler only after upstream success. */
        Object apply(Object input) throws Exception;
    }

    /**
     * Only the pipeline's own newly started asynchronous work should be
     * wrapped as Owned. An input Future and externally shared stage Futures
     * are borrowed observations and are never cancelled by this pipeline.
     */
    public record Owned(OresFuture<?> future) {
        public Owned {
            Objects.requireNonNull(future, "future");
        }
    }

    public static Owned owned(OresFuture<?> future) {
        return new Owned(future);
    }

    private static final class UpstreamCancelled extends CancellationException {
        private static final long serialVersionUID = 1L;
        private UpstreamCancelled() {
            super("pipeline upstream Future was cancelled");
        }
    }

    /**
     * Execute at most 128 statically checked stages. Stage lambdas must delay
     * *all* stage arguments and other side effects until their invocation.
     *
     * <p>Cancellation revokes the single pipeline task and its currently
     * owned stage Future, never the shared input. The returned observation
     * Future is distinct from the scheduler-internal quiescence witness;
     * consumers requiring physical retirement must still use the runtime
     * task/actor lifetime barrier.</p>
     */
    /** Convenience observation API; use startTracked when retirement matters. */
    public static <T> OresFuture<T> start(
            OresScheduler scheduler,
            OresFuture<?> input,
            List<? extends Stage> stages) {
        return TaskPipeline.<T>startTracked(scheduler, input, stages).result();
    }

    /** One owned, tracked scheduler Task. The Task's done barrier is separate. */
    public static <T> PipelineHandle<T> startTracked(
            OresScheduler scheduler,
            OresFuture<?> input,
            List<? extends Stage> stages) {
        Objects.requireNonNull(scheduler, "scheduler");
        return startInternal(scheduler::startTracked, input, stages);
    }

    /**
     * Admit the pipeline as one child of an explicit structured TaskScope.
     * Closing the scope requests cancellation without blocking a carrier.
     */
    public static <T> PipelineHandle<T> startInScope(
            TaskScope scope,
            OresFuture<?> input,
            List<? extends Stage> stages) {
        Objects.requireNonNull(scope, "scope");
        return startInternal(scope::startTask, input, stages);
    }

    @FunctionalInterface
    private interface TaskStarter<T> {
        TaskHandle<T> start(OresScheduler.Task<T> task);
    }

    @SuppressWarnings("unchecked")
    private static <T> PipelineHandle<T> startInternal(
            TaskStarter<T> starter,
            OresFuture<?> input,
            List<? extends Stage> stages) {
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(input, "input");
        List<Stage> chain = List.copyOf(Objects.requireNonNull(stages, "stages"));
        if (chain.size() > 128) {
            throw new IllegalArgumentException("pipeline exceeds 128 stages");
        }

        AtomicReference<OresFuture<?>> ownedStage = new AtomicReference<>();
        AtomicReference<OresFuture<T>> exposedRef = new AtomicReference<>();

        TaskHandle<T> tracked = starter.start(new OresScheduler.Task<>() {
            private boolean started;
            private int next;
            private OresFuture<?> awaited;

            @Override
            public OresScheduler.Step<T> resume(OresScheduler.Resume resume)
                    throws Exception {
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException("pipeline did not start on initial dispatch");
                    }
                    started = true;
                    armPipelineAwait(exposedRef.get(), input);
                    awaited = input;
                    return OresScheduler.await(input);
                }
                if (resume.initial() || awaited == null) {
                    throw new IllegalStateException("pipeline resumed without an await");
                }

                OresFuture<?> previous = awaited;
                awaited = null;
                retirePipelineAwait(exposedRef.get(), previous);
                // An owned child is no longer current once its await resumes;
                // a concurrent pipeline cancellation may already have
                // cancelled it. No later stage may reuse its ownership token.
                ownedStage.compareAndSet(previous, null);

                if (resume.failure() != null) {
                    if (previous.isCancelled()) throw new UpstreamCancelled();
                    Throwable failure = OresFuture.unwrap(resume.failure());
                    if (failure instanceof Exception exception) throw exception;
                    if (failure instanceof Error error) throw error;
                    throw new IllegalStateException("pipeline stage failed", failure);
                }

                Object value = resume.value();
                while (next < chain.size()) {
                    OresFuture<T> exposed = exposedRef.get();
                    if (exposed != null && exposed.isCancelled()) {
                        throw new UpstreamCancelled();
                    }
                    Object produced = chain.get(next++).apply(value);
                    if (produced instanceof Owned owned) {
                        OresFuture<?> child = owned.future();
                        if (child == exposedRef.get()) {
                            throw new IllegalStateException(
                                    "pipeline cannot await its own exposed completion Future");
                        }
                        if (child == input) {
                            throw new IllegalArgumentException(
                                    "pipeline cannot claim ownership of its borrowed input Future");
                        }
                        if (!ownedStage.compareAndSet(null, child)) {
                            throw new IllegalStateException(
                                    "pipeline already retains an owned stage Future");
                        }
                        exposed = exposedRef.get();
                        if (exposed != null && exposed.isCancelled()) {
                            if (ownedStage.compareAndSet(child, null)) child.cancel(false);
                            throw new UpstreamCancelled();
                        }
                        try {
                            armPipelineAwait(exposedRef.get(), child);
                        } catch (RuntimeException failure) {
                            if (ownedStage.compareAndSet(child, null)) child.cancel(false);
                            throw failure;
                        }
                        awaited = child;
                        return OresScheduler.await(child);
                    }
                    if (produced instanceof OresFuture<?> nextFuture) {
                        if (nextFuture == exposedRef.get()) {
                            throw new IllegalStateException(
                                    "pipeline cannot await its own exposed completion Future");
                        }
                        armPipelineAwait(exposedRef.get(), nextFuture);
                        awaited = nextFuture;
                        return OresScheduler.await(nextFuture);
                    }
                    value = produced;
                }
                return OresScheduler.done((T) value);
            }
        });

        OresFuture<T> task = tracked.result();
        OresFuture<T> exposed = new OresFuture<>(() -> {
            retirePipelineAwait(exposedRef.get(), null);
            tracked.cancel("pipeline cancelled");
            OresFuture<?> currentOwned = ownedStage.getAndSet(null);
            if (currentOwned != null) currentOwned.cancel(false);
        });
        exposedRef.set(exposed);

        AtomicReference<OresFuture.RuntimeWaiterRegistration<T>> subscription =
                new AtomicReference<>();
        OresFuture.RuntimeWaiterRegistration<T> registration =
                task.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (exposed.isDone()) return;
                        if (failure == null) {
                            exposed.completeFromRuntime(value);
                        } else if (task.isCancelled()
                                || OresFuture.unwrap(failure) instanceof UpstreamCancelled) {
                            exposed.cancel(false);
                        } else {
                            exposed.failFromRuntime(OresFuture.unwrap(failure));
                        }
                    } finally {
                        retirePipelineAwait(exposedRef.get(), null);
                        OresFuture<?> retiredOwned = ownedStage.getAndSet(null);
                        if (retiredOwned != null && exposed.isCancelled()) retiredOwned.cancel(false);
                        OresFuture.RuntimeWaiterRegistration<T> current =
                                subscription.getAndSet(null);
                        if (current != null) current.detach();
                    }
                });
        subscription.set(registration);
        if (exposed.isDone()) {
            OresFuture.RuntimeWaiterRegistration<T> raced =
                    subscription.getAndSet(null);
            if (raced != null) raced.detach();
            OresFuture<?> retiredOwned = ownedStage.getAndSet(null);
            if (retiredOwned != null && exposed.isCancelled()) retiredOwned.cancel(false);
        }
        return new PipelineHandle<>(exposed, tracked);
    }
}

package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A lazy, typed, single-use asynchronous waterfall.
 *
 * <p>Unlike a list of eager task { } values, each stage is invoked only after
 * the preceding stage's Future successfully produces a value. A single
 * OresScheduler Task drives the entire chain with ordinary await turns, so
 * no stage callback runs on an I/O, timer, channel or producer-completion
 * thread and no carrier is parked while a Future is pending.</p>
 *
 * <p>The builder is host-runtime staging, NOT an Oreslang source intrinsic.
 * Source lowering must additionally enforce effects, Send/Sync, ownership,
 * actor confinement, and the semantics of the existing |> operator.</p>
 */
public final class TaskWaterfall {
    /** Bound the plan before constructing the scheduler's state machine. */
    public static final int MAX_STAGES = 65_536;

    private TaskWaterfall() { }

    @FunctionalInterface
    private interface Stage {
        OresFuture<?> invoke(Object previous);
    }

    /** Immutable reverse-linked plan: growing a pipeline is O(1) per stage. */
    private record Link(Link parent, Stage stage) { }

    /**
     * Diagnostics are separate from the original failed Future: they do NOT
     * wrap, mutate, catch, or downcast its Throwable. Source-level throws,
     * raises and panics classification is a different compiler contract.
     */
    public record StageFailure(int stageIndex, Throwable cause, boolean cancelled) {
        public StageFailure {
            if (stageIndex < 0) {
                throw new IllegalArgumentException("negative waterfall stage index");
            }
            Objects.requireNonNull(cause, "cause");
        }
    }

    /**
     * A pipeline carries the exact generic type of its latest stage.
     * All prefixes of a pipeline share one single-use admission token, so
     * extending a plan cannot double-submit an owned deferred computation.
     */
    public static final class Pipeline<T> {
        private final Link tail;
        private final int size;
        private final AtomicBoolean admitted;
        private final AtomicReference<StageFailure> firstFailure;

        private Pipeline(Link tail, int size, AtomicBoolean admitted,
                         AtomicReference<StageFailure> firstFailure) {
            this.tail = tail;
            this.size = size;
            this.admitted = admitted;
            this.firstFailure = firstFailure;
        }

        /**
         * Once the waterfall Task has physically retired, this identifies
         * the failing stage (zero-based). Genuine failure retains the exact
         * original cause, while source cancellation is authenticated by the
         * source Future's cancellation state. External owner cancellation
         * may have no failing stage.
         */
        public Optional<StageFailure> failureContext() {
            return Optional.ofNullable(firstFailure.get());
        }

        /** Apply a lazy asynchronous step to the preceding result. */
        public <U> Pipeline<U> then(
                Function<? super T, ? extends OresFuture<? extends U>> next) {
            Objects.requireNonNull(next, "next");
            if (size >= MAX_STAGES) {
                throw new IllegalArgumentException("waterfall exceeds stage limit");
            }
            Stage step = previous -> {
                @SuppressWarnings("unchecked")
                T value = (T) previous;
                return Objects.requireNonNull(next.apply(value),
                        "waterfall async stage returned null Future");
            };
            return new Pipeline<>(new Link(tail, step), size + 1, admitted, firstFailure);
        }

        /**
         * Await a value through the same Awaitable projection used by
         * compiler-lowered await. Host adapters can return a Future or
         * another checked awaitable without exposing guest continuations
         * to producer notification threads.
         */
        public <U> Pipeline<U> thenAwaitable(
                Function<? super T, ? extends Awaitable<? extends U>> next) {
            Objects.requireNonNull(next, "next");
            return then(value -> Objects.requireNonNull(
                    Objects.requireNonNull(next.apply(value),
                            "waterfall stage returned null Awaitable").getAwaited(),
                    "waterfall awaitable projected a null Future"));
        }

        /** Apply a lazy synchronous transform without an additional executor. */
        public <U> Pipeline<U> thenValue(Function<? super T, ? extends U> next) {
            Objects.requireNonNull(next, "next");
            return then(value -> OresFuture.completed(next.apply(value)));
        }

        /** Start the whole pipeline as ONE tracked scheduler Task. */
        public TaskHandle<T> start(OresScheduler scheduler) {
            Objects.requireNonNull(scheduler, "scheduler");
            if (!admitted.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "a Task.waterfall factory or one of its prefixes already started");
            }

            Stage[] stages = new Stage[size];
            Link cursor = tail;
            for (int index = size - 1; index >= 0; index--) {
                stages[index] = cursor.stage();
                cursor = cursor.parent();
            }
            if (cursor != null) throw new IllegalStateException("waterfall plan is cyclic");

            AtomicReference<TaskHandle<T>> owner = new AtomicReference<>();
            // Stage 0 cannot start until the owner TaskHandle is published.
            // A pre-cancelled source must be classified by an authenticated
            // owner cancellation checkpoint, not as a user-raised failure
            // simply because inline startup ran before handle binding.
            OresFuture<Void> ownerReady = new OresFuture<>();
            AtomicReference<OresFuture.RuntimeWaiterRegistration<?>> stageWaiter =
                    new AtomicReference<>();
            AtomicBoolean upstreamCancelled = new AtomicBoolean();

            OresScheduler.Task<T> driver = new OresScheduler.Task<>() {
                private int nextStage;
                private boolean started;
                private boolean ownerReadyObserved;

                private void detachWaiter() {
                    OresFuture.RuntimeWaiterRegistration<?> previous =
                            stageWaiter.getAndSet(null);
                    if (previous != null) previous.detach();
                }

                private OresScheduler.Step<T> invokeNext(Object previous) {
                    if (nextStage >= stages.length) {
                        throw new IllegalStateException("waterfall exhausted stage plan");
                    }
                    int stageIndex = nextStage++;
                    Stage step = stages[stageIndex];
                    final OresFuture<?> future;
                    try {
                        future = Objects.requireNonNull(
                                step.invoke(previous), "waterfall stage returned null Future");
                    } catch (RuntimeException | Error thrown) {
                        firstFailure.compareAndSet(
                                null, new StageFailure(stageIndex, thrown, false));
                        throw thrown;
                    }

                    // This watcher carries only runtime cancellation bookkeeping.
                    // Cancelling a borrowed stage Future does not grant the
                    // waterfall authority to cancel that stage's producer.
                    OresFuture.RuntimeWaiterRegistration<?> watcher =
                            future.whenCompleteRuntimeCancellable((value, failure) -> {
                                if (!future.isCancelled()) return;
                                firstFailure.compareAndSet(null,
                                        new StageFailure(stageIndex,
                                                failure != null ? failure :
                                                        new java.util.concurrent.CancellationException(
                                                                "waterfall source cancelled"),
                                                true));
                                upstreamCancelled.set(true);
                                TaskHandle<T> active = owner.get();
                                if (active != null) active.cancel("waterfall stage cancelled");
                            });
                    stageWaiter.set(watcher);
                    // A source may finish (including cancellation) before
                    // the watcher is stored. Preserve its authenticated
                    // cancellation state before releasing the registration.
                    // Cancellation revocation is a separate barrier from
                    // Future.isDone(): the controller only requests its own
                    // cancellation, never revokes the shared stage source.
                    if (future.isDone()) {
                        if (future.isCancelled()) {
                            firstFailure.compareAndSet(null, new StageFailure(
                                    stageIndex,
                                    new java.util.concurrent.CancellationException(
                                            "waterfall stage cancelled"), true));
                            upstreamCancelled.set(true);
                            TaskHandle<T> active = owner.get();
                            if (active != null) {
                                active.cancel("waterfall stage cancelled");
                            }
                        }
                        if (stageWaiter.compareAndSet(watcher, null)) {
                            watcher.detach();
                        }
                    }
                    return OresScheduler.await(future);
                }

                @Override
                public OresScheduler.Step<T> resume(OresScheduler.Resume resume)
                        throws Exception {
                    OresScheduler.cancellationCheckpoint();
                    if (!started) {
                        if (!resume.initial()) {
                            throw new IllegalStateException(
                                    "waterfall started with noninitial resume");
                        }
                        started = true;
                        // Even an inline scheduler must fully publish the
                        // TaskHandle before a user-supplied stage is entered.
                        return OresScheduler.await(ownerReady);
                    }

                    if (resume.initial()) {
                        throw new IllegalStateException(
                                "waterfall resumed with duplicate initial event");
                    }
                    if (!ownerReadyObserved) {
                        ownerReadyObserved = true;
                        if (resume.failure() != null) {
                            throw new IllegalStateException(
                                    "waterfall ownership admission rejected",
                                    OresFuture.unwrap(resume.failure()));
                        }
                        return invokeNext(null);
                    }
                    detachWaiter();
                    if (resume.failure() != null) {
                        Throwable failure = OresFuture.unwrap(resume.failure());
                        firstFailure.compareAndSet(null,
                                new StageFailure(nextStage - 1, failure, false));
                        if (failure instanceof Exception exception) throw exception;
                        if (failure instanceof Error error) throw error;
                        throw new IllegalStateException(
                                "unexpected nonexception Throwable from waterfall", failure);
                    }
                    if (nextStage == stages.length) {
                        @SuppressWarnings("unchecked")
                        T value = (T) resume.value();
                        return OresScheduler.done(value);
                    }
                    return invokeNext(resume.value());
                }
            };

            final TaskHandle<T> handle;
            try {
                handle = scheduler.startTracked(driver);
            } catch (RuntimeException | Error admissionFailure) {
                OresFuture.RuntimeWaiterRegistration<?> pending =
                        stageWaiter.getAndSet(null);
                if (pending != null) pending.detach();
                throw admissionFailure;
            }

            owner.set(handle);
            // Physical completion and descendant retirement, not mere Future
            // settlement, closes the subscribed stage lifetime.
            handle.getDone().whenCompleteRuntime((outcome, failure) -> {
                OresFuture.RuntimeWaiterRegistration<?> pending =
                        stageWaiter.getAndSet(null);
                if (pending != null) pending.detach();
            });
            // Resume the initial scheduler turn ONLY after ownership and
            // cleanup subscriptions are registered; completion may wake the
            // original Task from another host thread but never executes guest
            // code there.
            ownerReady.completeFromRuntime(null);
            if (upstreamCancelled.get()) handle.cancel("waterfall stage cancelled");
            return handle;
        }
    }

    /** The zero-argument first stage is a deferred factory, not a() evaluated eagerly. */
    public static <A> Pipeline<A> from(
            Supplier<? extends OresFuture<? extends A>> first) {
        Objects.requireNonNull(first, "first");
        Stage initial = ignored -> Objects.requireNonNull(first.get(),
                "waterfall initial stage returned null Future");
        return new Pipeline<>(new Link(null, initial), 1, new AtomicBoolean(),
                new AtomicReference<>());
    }

    /** Convenience: 3 heterogeneous, lazily executed asynchronous stages. */
    public static <A, B, C> TaskHandle<C> waterfall(
            OresScheduler scheduler,
            Supplier<? extends OresFuture<? extends A>> a,
            Function<? super A, ? extends OresFuture<? extends B>> b,
            Function<? super B, ? extends OresFuture<? extends C>> c) {
        return TaskWaterfall.<A>from(a).then(b).then(c).start(scheduler);
    }

    /** Convenience: 4 heterogeneous, lazily executed asynchronous stages. */
    public static <A, B, C, D> TaskHandle<D> waterfall(
            OresScheduler scheduler,
            Supplier<? extends OresFuture<? extends A>> a,
            Function<? super A, ? extends OresFuture<? extends B>> b,
            Function<? super B, ? extends OresFuture<? extends C>> c,
            Function<? super C, ? extends OresFuture<? extends D>> d) {
        return TaskWaterfall.<A>from(a).then(b).then(c).then(d).start(scheduler);
    }
}

package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Host/runtime plumbing for composition of already-started OresFuture values.
 *
 * <p>These methods observe sources; they never spawn tasks, execute guest code,
 * acquire source cancellation authority, or run a guest continuation from the
 * source completion thread. The returned Future is independently cancellable:
 * cancelling it detaches its observations, not the source operations.</p>
 *
 * <p>This class is NOT a guest-language intrinsic registration. Source-level
 * Future.all and Task.parallel require separately checked lowering.</p>
 */
public final class FutureCombinators {
    private static final int MAX_FAN_IN = 65_536;

    private FutureCombinators() { }

    public enum Status { SUCCESS, FAILURE, CANCELLED }

    public record Outcome<T>(Status status, T value, Throwable failure) {
        public Outcome {
            Objects.requireNonNull(status, "status");
            if (status == Status.SUCCESS && failure != null) {
                throw new IllegalArgumentException("success must not contain a failure");
            }
            if (status != Status.SUCCESS && failure == null) {
                throw new IllegalArgumentException("failure/cancellation requires a cause");
            }
        }
    }

    private static <T> List<? extends OresFuture<? extends T>> snapshot(
            List<? extends OresFuture<? extends T>> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() > MAX_FAN_IN) {
            throw new IllegalArgumentException("too many Futures in one composition");
        }
        // Reject nulls and take an immutable snapshot BEFORE installing listeners.
        return List.copyOf(inputs);
    }

    private static void detachAll(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations) {
        for (int i = 0; i < registrations.length(); i++) {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    registrations.getAndSet(i, null);
            if (registration != null) registration.detach();
        }
    }

    private static void retainUntilPendingOrDetach(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations,
            int index, OresFuture.RuntimeWaiterRegistration<?> registration,
            OresFuture<?> aggregate) {
        registrations.set(index, registration);
        // A settled source can notify inline before subscription returns; the
        // terminal callback's cleanup may have observed an empty slot.
        if (aggregate.isDone()) {
            registration.detach();
            registrations.compareAndSet(index, registration, null);
        }
    }

    /**
     * Analog of Promise.all for already-running operations. Result order is
     * input order, regardless of completion order. Empty input succeeds.
     *
     * <p>First observed failure fails the aggregate; first observed source
     * cancellation cancels it. Other source operations remain untouched.
     * These are OBSERVATION semantics, not a structured child join/quiescence
     * guarantee. The caller must own a TaskScope for owned child cancellation.</p>
     */
    public static <T> OresFuture<List<T>> all(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) return OresFuture.completed(List.of());
        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<List<T>> aggregate = new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            final int index = i;
            OresFuture<? extends T> source = sources.get(index);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (aggregate.isDone()) return;
                        if (failure != null) {
                            if (source.isCancelled()) {
                                aggregate.cancel(false);
                            } else if (aggregate.failFromRuntime(failure)) {
                                detachAll(registrations);
                            }
                            return;
                        }
                        values.set(index, value);
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<T> ordered = new ArrayList<>(n);
                            for (int j = 0; j < n; j++) {
                                @SuppressWarnings("unchecked")
                                T item = (T) values.get(j);
                                ordered.add(item);
                            }
                            if (aggregate.completeFromRuntime(
                                    Collections.unmodifiableList(ordered))) {
                                detachAll(registrations);
                            }
                        }
                    });
            retainUntilPendingOrDetach(registrations, index, subscription, aggregate);
        }
        return aggregate;
    }

    /**
     * Wait for every input, including failed and cancelled inputs, returning
     * outcomes in input order. Only cancellation of the aggregate itself
     * detaches it early; input failures never short-circuit allSettled.
     */
    public static <T> OresFuture<List<Outcome<T>>> allSettled(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) return OresFuture.completed(List.of());
        AtomicReferenceArray<Outcome<T>> outcomes = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<List<Outcome<T>>> aggregate =
                new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            final int index = i;
            OresFuture<? extends T> source = sources.get(index);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (aggregate.isDone()) return;
                        Status status = failure == null ? Status.SUCCESS
                                : source.isCancelled() ? Status.CANCELLED : Status.FAILURE;
                        outcomes.set(index, new Outcome<>(status, value, failure));
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<Outcome<T>> ordered = new ArrayList<>(n);
                            for (int j = 0; j < n; j++) ordered.add(outcomes.get(j));
                            if (aggregate.completeFromRuntime(List.copyOf(ordered))) {
                                detachAll(registrations);
                            }
                        }
                    });
            retainUntilPendingOrDetach(registrations, index, subscription, aggregate);
        }
        return aggregate;
    }




    /**
     * Authenticated upstream cancellation moves downstream without granting
     * the downstream task the right to cancel the shared source. Install the
     * cancellation witness BEFORE admitting/awaiting the dependent Task so
     * it wins the pending-source notification order. Detach the witness once
     * the dependent result settles, including on admission failure.
     */
    private static <U> OresFuture<U> startObservingCancellation(
            OresFuture<?> source,
            java.util.function.Supplier<OresFuture<U>> taskFactory) {
        java.util.concurrent.atomic.AtomicBoolean upstreamCancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<OresFuture<U>> taskRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        OresFuture.RuntimeWaiterRegistration<?> waiter =
                source.whenCompleteRuntimeCancellable((value, failure) -> {
                    if (!source.isCancelled()) return;
                    upstreamCancelled.set(true);
                    OresFuture<U> active = taskRef.get();
                    if (active != null) active.cancel(false);
                });

        if (upstreamCancelled.get()) {
            waiter.detach();
            OresFuture<U> cancelled = new OresFuture<>();
            cancelled.cancel(false);
            return cancelled;
        }

        final OresFuture<U> dependent;
        try {
            dependent = Objects.requireNonNull(taskFactory.get(),
                    "task factory returned null Future");
        } catch (RuntimeException | Error failure) {
            waiter.detach();
            throw failure;
        }

        taskRef.set(dependent);
        dependent.whenCompleteRuntime((value, failure) -> waiter.detach());
        if (upstreamCancelled.get()) dependent.cancel(false);
        return dependent;
    }

    /**
     * Transform one settled Future on the explicitly supplied scheduler.
     * The source is only observed: cancelling the dependent Task must never
     * cancel a source that may be shared elsewhere.
     *
     * <p>Unlike CompletableFuture.thenApply, the mapper NEVER executes from
     * the producer's I/O, timer, JNI or notification thread. Source-level
     * mappers additionally require compiler-proven captures/effect admission;
     * this is trusted HOST runtime plumbing only.</p>
     */
    public static <T, U> OresFuture<U> map(
            OresScheduler scheduler, OresFuture<? extends T> source,
            java.util.function.Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");
        return startObservingCancellation(source, () ->
                scheduler.start(new OresScheduler.Task<>() {
            private boolean started;

            @Override
            public OresScheduler.Step<U> resume(OresScheduler.Resume resume) {
                if (!started) {
                    if (!resume.initial()) {
                        throw new IllegalStateException("map started with noninitial resume");
                    }
                    started = true;
                    return OresScheduler.await(source);
                }
                if (resume.initial()) {
                    throw new IllegalStateException("map resumed with duplicate initial event");
                }
                if (resume.failure() != null) throw propagate(resume.failure());
                @SuppressWarnings("unchecked")
                T value = (T) resume.value();
                return OresScheduler.done(mapper.apply(value));
            }
        }));
    }

    /**
     * Scheduler-owned asynchronous flatMap; waits for the source, executes
     * the mapper on the scheduler, then waits for the returned Future without
     * parking a physical carrier. No nested Future is implicitly cancelled:
     * an owning TaskScope must handle owned work cancellation and quiescence.
     */
    public static <T, U> OresFuture<U> flatMap(
            OresScheduler scheduler, OresFuture<? extends T> source,
            java.util.function.Function<? super T, ? extends OresFuture<? extends U>> mapper) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(mapper, "mapper");

        java.util.concurrent.atomic.AtomicReference<OresFuture<U>> dependentRef =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<
                OresFuture.RuntimeWaiterRegistration<?>> innerWaiter =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean innerCancelled =
                new java.util.concurrent.atomic.AtomicBoolean();

        OresFuture<U> dependent = startObservingCancellation(source, () ->
                scheduler.start(new OresScheduler.Task<>() {
            private int phase;

            @Override
            public OresScheduler.Step<U> resume(OresScheduler.Resume resume) {
                if (phase == 0) {
                    if (!resume.initial()) {
                        throw new IllegalStateException(
                                "flatMap started with noninitial resume");
                    }
                    phase = 1;
                    return OresScheduler.await(source);
                }
                if (resume.initial()) {
                    throw new IllegalStateException(
                            "flatMap resumed with duplicate initial event");
                }
                if (resume.failure() != null) throw propagate(resume.failure());
                if (phase == 1) {
                    @SuppressWarnings("unchecked")
                    T value = (T) resume.value();
                    OresFuture<? extends U> next = Objects.requireNonNull(
                            mapper.apply(value), "flatMap mapper returned null Future");
                    // Only authenticated cancellation of the inner producer
                    // cancels this downstream Task. A thrown
                    // CancellationException that did not cancel the inner
                    // Future remains an ordinary failure.
                    OresFuture.RuntimeWaiterRegistration<?> watcher =
                            next.whenCompleteRuntimeCancellable((ignored, failure) -> {
                                if (!next.isCancelled()) return;
                                innerCancelled.set(true);
                                OresFuture<U> active = dependentRef.get();
                                if (active != null) active.cancel(false);
                            });
                    innerWaiter.set(watcher);
                    // A concurrent dependent cancellation can drain its
                    // terminal subscribers BEFORE the inner subscription is
                    // published. Retire any late registration immediately.
                    OresFuture<U> terminalDependent = dependentRef.get();
                    if (terminalDependent != null && terminalDependent.isDone()) {
                        if (innerWaiter.compareAndSet(watcher, null)) {
                            watcher.detach();
                        }
                    }
                    if (innerCancelled.get()) {
                        OresFuture<U> active = dependentRef.get();
                        if (active != null) active.cancel(false);
                    }
                    phase = 2;
                    return OresScheduler.await(next);
                }
                if (phase == 2) {
                    @SuppressWarnings("unchecked")
                    U value = (U) resume.value();
                    phase = 3;
                    return OresScheduler.done(value);
                }
                throw new IllegalStateException("flatMap resumed after completion");
            }
        }));

        dependentRef.set(dependent);
        dependent.whenCompleteRuntime((value, failure) -> {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    innerWaiter.getAndSet(null);
            if (registration != null) registration.detach();
        });
        if (innerCancelled.get()) dependent.cancel(false);
        // An inner Future can settle synchronously before its waiter is
        // stored. No late notification can pin that completed inner source.
        if (dependent.isDone()) {
            OresFuture.RuntimeWaiterRegistration<?> registration =
                    innerWaiter.getAndSet(null);
            if (registration != null) registration.detach();
        }
        return dependent;
    }

    private static RuntimeException propagate(Throwable failure) {
        Throwable original = OresFuture.unwrap(failure);
        if (original instanceof RuntimeException runtime) return runtime;
        if (original instanceof Error error) throw error;
        return new java.util.concurrent.CompletionException(original);
    }

    /** A typed two-value result; no Any-erasure in the public API. */
    public record Pair<A, B>(A first, B second) { }

    /**
     * Heterogeneous ordered fan-in via the same cancellation-safe all()
     * observer and scheduler-owned mapper. Future values are never restarted.
     */
    public static <A, B> OresFuture<Pair<A, B>> zip(
            OresScheduler scheduler, OresFuture<? extends A> a,
            OresFuture<? extends B> b) {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        List<OresFuture<? extends Object>> pair = List.of(a, b);
        return map(scheduler, FutureCombinators.<Object>all(pair), values -> {
            @SuppressWarnings("unchecked")
            A first = (A) values.get(0);
            @SuppressWarnings("unchecked")
            B second = (B) values.get(1);
            return new Pair<>(first, second);
        });
    }

    /**
     * First SUCCESS wins; unlike race, failures/cancellations are ignored until
     * all candidates fail. Empty input is an invalid composition.
     *
     * <p>When all sources are cancelled, the aggregate is cancelled. A mix
     * containing at least one genuine failure completes exceptionally with an
     * input-ordered list of causes. No source is cancelled or restarted by
     * this non-owning observer.</p>
     */
    public static <T> OresFuture<T> any(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) throw new IllegalArgumentException("any requires at least one Future");
        AtomicReferenceArray<Throwable> failures = new AtomicReferenceArray<>(n);
        AtomicInteger remaining = new AtomicInteger(n);
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<T> aggregate = new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            final int index = i;
            OresFuture<? extends T> source = sources.get(index);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (aggregate.isDone()) return;
                        if (failure == null) {
                            if (aggregate.completeFromRuntime(value)) {
                                detachAll(registrations);
                            }
                            return;
                        }
                        failures.set(index, failure);
                        if (source.isCancelled()) cancellations.incrementAndGet();
                        if (remaining.decrementAndGet() != 0) return;

                        if (cancellations.get() == n) {
                            aggregate.cancel(false);
                            return;
                        }
                        ArrayList<Throwable> ordered = new ArrayList<>(n);
                        for (int j = 0; j < n; j++) ordered.add(failures.get(j));
                        AnyFailedException allFailed = new AnyFailedException(ordered);
                        if (aggregate.failFromRuntime(allFailed)) {
                            detachAll(registrations);
                        }
                    });
            retainUntilPendingOrDetach(registrations, index, subscription, aggregate);
        }
        return aggregate;
    }

    /** All candidates terminated without a successful value. */
    public static final class AnyFailedException extends IllegalStateException {
        private final List<Throwable> failures;

        private AnyFailedException(List<Throwable> failures) {
            super("no Future in any() completed successfully");
            this.failures = List.copyOf(failures);
            for (Throwable failure : this.failures) addSuppressed(failure);
        }

        /** Input-ordered causes, including source cancellations. */
        public List<Throwable> failures() {
            return failures;
        }
    }

    /**
     * First observed terminal outcome wins. Subscriptions on already-settled
     * inputs are installed in input order, making their tie-break deterministic.
     * An empty race is rejected rather than creating a forever-pending Future.
     * Losing inputs are never cancelled by this observer.
     */
    public static <T> OresFuture<T> race(
            List<? extends OresFuture<? extends T>> inputs) {
        List<? extends OresFuture<? extends T>> sources = snapshot(inputs);
        int n = sources.size();
        if (n == 0) throw new IllegalArgumentException("race requires at least one Future");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration<?>> registrations =
                new AtomicReferenceArray<>(n);
        OresFuture<T> aggregate = new OresFuture<>(() -> detachAll(registrations));

        for (int i = 0; i < n; i++) {
            if (aggregate.isDone()) break;
            OresFuture<? extends T> source = sources.get(i);
            OresFuture.RuntimeWaiterRegistration<?> subscription =
                    source.whenCompleteRuntimeCancellable((value, failure) -> {
                        boolean won;
                        if (failure == null) won = aggregate.completeFromRuntime(value);
                        else if (source.isCancelled()) won = aggregate.cancel(false);
                        else won = aggregate.failFromRuntime(failure);
                        if (won) detachAll(registrations);
                    });
            retainUntilPendingOrDetach(registrations, i, subscription, aggregate);
        }
        return aggregate;
    }
}

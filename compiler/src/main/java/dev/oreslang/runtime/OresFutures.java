package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Structured combinators for language-level Future<T> values.
 *
 * <p>The public language primitive is {@link OresFuture}. CompletionStage is
 * accepted only as a host-interop input and is immediately normalized into an
 * OresFuture so dependent Oreslang work never inherits a host callback
 * execution policy.</p>
 *
 * <p>These combinators observe already-started Futures. Cancellation of
 * an aggregate detaches its waiters, NEVER cancelling its borrowed input
 * sources; only explicit owners such as TaskScope may revoke work.</p>
 */
public final class OresFutures {
    // Bound listener and input allocations for both guest and host adapters.
    // Keep parity with FutureCombinators; a source TaskScope can impose a
    // tighter actor/tenant-specific budget before invoking this helper.
    public static final int MAX_FAN_IN = 65_536;

    private OresFutures() { }

    public record Settled<T>(T value, Throwable error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static <T> OresFuture<List<T>> all(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters =
                new AtomicReferenceArray<>(children.size());
        Runnable detachWaiters = () -> detachAll(waiters);

        OresFuture<List<T>> result = new OresFuture<>(() -> {
            // An aggregate only OBSERVES borrowed input Futures. Revoking
            // the observer must never cancel a shared Task or actor reply.
            // Structured child cancellation belongs to an owning TaskScope.
            detachWaiters.run();
        });
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Object> values = new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntimeCancellable((value, failure) -> {
                        if (result.isDone()) {
                            detachWaiters.run();
                            return;
                        }
                        if (failure != null) {
                            if (children.get(slot).isCancelled()) {
                                result.cancel(false);
                            } else {
                                result.failFromRuntime(OresFuture.unwrap(failure));
                            }
                            detachWaiters.run();
                            return;
                        }
                        values.set(slot, value);
                        if (remaining.decrementAndGet() == 0) {
                            ArrayList<T> ordered = new ArrayList<>(children.size());
                            for (int i = 0; i < children.size(); i++) {
                                @SuppressWarnings("unchecked")
                                T item = (T) values.get(i);
                                ordered.add(item);
                            }
                            // Void futures complete with null; preserve their
                            // slots while keeping the aggregate immutable.
                            result.completeFromRuntime(Collections.unmodifiableList(ordered));
                            detachWaiters.run();
                        }
                    });
            waiters.set(slot, registration);
            if (result.isDone()) {
                OresFuture.RuntimeWaiterRegistration raced =
                        waiters.getAndSet(slot, null);
                if (raced != null) raced.detach();
            }
        }
        return result;
    }

    public static <T> OresFuture<T> race(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.race");
        if (children.isEmpty()) {
            return OresFuture.failed(
                    new IllegalArgumentException("Futures.race requires at least one future"));
        }

        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters =
                new AtomicReferenceArray<>(children.size());
        Runnable detachWaiters = () -> detachAll(waiters);

        OresFuture<T> result = new OresFuture<>(() -> {
            // An aggregate only OBSERVES borrowed input Futures. Revoking
            // the observer must never cancel a shared Task or actor reply.
            // Structured child cancellation belongs to an owning TaskScope.
            detachWaiters.run();
        });

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture<T> child = children.get(index);
            OresFuture.RuntimeWaiterRegistration registration =
                    child.whenCompleteRuntimeCancellable((value, failure) -> {
                        if (result.isDone()) {
                            detachWaiters.run();
                            return;
                        }

                        if (failure == null) {
                            result.completeFromRuntime(value);
                        } else if (child.isCancelled()) {
                            result.cancel(false);
                        } else {
                            result.failFromRuntime(OresFuture.unwrap(failure));
                        }
                        detachWaiters.run();
                    });
            waiters.set(slot, registration);
            if (result.isDone()) {
                OresFuture.RuntimeWaiterRegistration raced =
                        waiters.getAndSet(slot, null);
                if (raced != null) raced.detach();
            }
        }
        return result;
    }

    public static <T> OresFuture<List<Settled<T>>> allSettled(List<?> awaitables) {
        List<OresFuture<T>> children = normalize(awaitables, "Futures.all_settled");
        AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters =
                new AtomicReferenceArray<>(children.size());
        Runnable detachWaiters = () -> detachAll(waiters);
        OresFuture<List<Settled<T>>> result = new OresFuture<>(() -> {
            // An aggregate only OBSERVES borrowed input Futures. Revoking
            // the observer must never cancel a shared Task or actor reply.
            // Structured child cancellation belongs to an owning TaskScope.
            detachWaiters.run();
        });
        if (children.isEmpty()) {
            result.completeFromRuntime(List.of());
            return result;
        }

        AtomicReferenceArray<Settled<T>> settled =
                new AtomicReferenceArray<>(children.size());
        AtomicInteger remaining = new AtomicInteger(children.size());

        for (int index = 0; index < children.size(); index++) {
            int slot = index;
            OresFuture.RuntimeWaiterRegistration registration =
                    children.get(index).whenCompleteRuntimeCancellable((value, failure) -> {
                            if (result.isDone()) {
                                detachWaiters.run();
                                return;
                            }
                            settled.set(
                                    slot,
                                    new Settled<>(
                                            failure == null ? value : null,
                                            failure == null ? null : OresFuture.unwrap(failure)));
                            if (remaining.decrementAndGet() == 0) {
                                ArrayList<Settled<T>> ordered =
                                        new ArrayList<>(children.size());
                                for (int i = 0; i < children.size(); i++) {
                                    ordered.add(settled.get(i));
                                }
                                result.completeFromRuntime(List.copyOf(ordered));
                                detachWaiters.run();
                            }
                        });
            waiters.set(slot, registration);
            if (result.isDone()) {
                OresFuture.RuntimeWaiterRegistration raced =
                        waiters.getAndSet(slot, null);
                if (raced != null) raced.detach();
            }
        }
        return result;
    }

    /**
     * Cancellation-aware replacement for the legacy Settled(value,error)
     * view. It preserves SUCCESS, FAILURE and runtime-authenticated CANCELLED
     * independently, including failures whose Throwable happens to be a
     * CancellationException. It does not cancel borrowed input sources.
     *
     * <p>Host-runtime staging only. The source compiler must enforce checked
     * throws/raises/panics before exposing this as a guest allSettled: an
     * Oreslang panic may never be reclassified as a catchable failure.</p>
     */
    public static <T> OresFuture<List<FutureCombinators.Outcome<T>>>
            allSettledDetailed(List<?> awaitables) {
        List<OresFuture<T>> children =
                normalize(awaitables, "Futures.all_settled_detailed");
        return FutureCombinators.allSettled(children);
    }

    private static void detachAll(
            AtomicReferenceArray<OresFuture.RuntimeWaiterRegistration> waiters) {
        for (int i = 0; i < waiters.length(); i++) {
            OresFuture.RuntimeWaiterRegistration registration =
                    waiters.getAndSet(i, null);
            if (registration != null) registration.detach();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<OresFuture<T>> normalize(
            List<?> awaitables,
            String operation) {
        Objects.requireNonNull(awaitables, "awaitables");
        if (awaitables.size() > MAX_FAN_IN) {
            throw new IllegalArgumentException(
                    operation + " exceeds maximum fan-in " + MAX_FAN_IN);
        }
        // Do not iterate a mutable, caller-owned List while installing
        // runtime waiters. Snapshot all arguments before any normalization
        // or host completion-stage subscription is created.
        List<?> snapshot = List.copyOf(awaitables);
        ArrayList<OresFuture<T>> children = new ArrayList<>(snapshot.size());
        for (Object awaitable : snapshot) {
            Objects.requireNonNull(awaitable, "future");
            if (awaitable instanceof OresFuture<?> ores) {
                children.add((OresFuture<T>) ores);
            } else if (awaitable instanceof CompletionStage<?> stage) {
                children.add(OresFuture.from((CompletionStage<? extends T>) stage));
            } else {
                throw new IllegalArgumentException(
                        operation + " expects every list element to be a Future");
            }
        }
        return List.copyOf(children);
    }
}

package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-owning fan-in of results already associated with actor requests.
 *
 * <p>Works equally for persistent-actor request/reply Futures (once a
 * verified request/reply transport is available) and one-shot actor
 * invocation results. This class does NOT send messages, construct
 * ActorRef capabilities, stop actors or own actor lifecycles. A caller must
 * supply trusted request identifiers plus the originating ActorAddress.</p>
 *
 * <p>Host-only bridge: source-language panic/raise effect metadata and
 * actor request/reply transport correlation are not yet implemented here.
 * Do not accept guest-provided arbitrary ActorAddress metadata as proof
 * of an authenticated actor reply.</p>
 */
public final class ActorResults {
    public static final int MAX_FAN_IN = 65_536;

    private ActorResults() { }

    /** A unique reply correlation with an actual, already-started Future. */
    public record Pending<R>(
            ActorRuntime.ActorAddress address,
            UUID requestId,
            OresFuture<? extends R> response) {
        public Pending {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(response, "response");
        }
    }

    /** Preserve the actor identity and request correlation alongside each value. */
    public record Reply<R>(
            ActorRuntime.ActorAddress address,
            UUID requestId,
            R value) {
        public Reply {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(requestId, "requestId");
            // Null is a valid host success for lifecycle Future<Void>.
        }
    }

    /**
     * Independently settled, per-actor result. Status is AUTHENTICATED by
     * OresFuture's terminal state; a guest-raised CancellationException is
     * Status.FAILURE, not Status.CANCELLED.
     */
    public record Settled<R>(
            ActorRuntime.ActorAddress address,
            UUID requestId,
            FutureCombinators.Status status,
            R value,
            Throwable error) {
        public Settled {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(status, "status");
            if (status == FutureCombinators.Status.SUCCESS && error != null) {
                throw new IllegalArgumentException("success cannot contain error");
            }
            if (status != FutureCombinators.Status.SUCCESS && error == null) {
                throw new IllegalArgumentException("failed/cancelled reply needs a cause");
            }
        }
    }

    private static <R> List<Pending<R>> snapshot(List<? extends Pending<R>> replies) {
        Objects.requireNonNull(replies, "replies");
        if (replies.size() > MAX_FAN_IN) {
            throw new IllegalArgumentException("actor fan-in exceeds " + MAX_FAN_IN);
        }
        // Capture an immutable input snapshot BEFORE installing a single
        // reply observer; a caller-owned mutable list is not stable while
        // another Task or actor appends new requests.
        List<? extends Pending<R>> fixed = List.copyOf(replies);
        ArrayList<Pending<R>> snapshot = new ArrayList<>(fixed.size());
        Set<UUID> requests = new HashSet<>();
        for (Pending<R> entry : fixed) {
            Objects.requireNonNull(entry, "actor reply");
            if (!requests.add(entry.requestId())) {
                throw new IllegalArgumentException(
                        "duplicate actor request correlation ID: " + entry.requestId());
            }
            snapshot.add(entry);
        }
        return List.copyOf(snapshot);
    }

    /**
     * Fail fast on the first failed response; retain input-ordered successful
     * values with their ACTOR+REQUEST metadata. Never cancels actor refs or
     * response producers, even when the aggregate is explicitly cancelled.
     * No physical actor-reclamation guarantee is conveyed.
     */
    public static <R> OresFuture<List<Reply<R>>> gather(
            List<? extends Pending<R>> replies) {
        List<Pending<R>> input = snapshot(replies);
        ArrayList<OresFuture<? extends R>> futures = new ArrayList<>(input.size());
        for (Pending<R> item : input) futures.add(item.response());
        OresFuture<List<R>> observed = FutureCombinators.all(futures);
        return project(observed, values -> {
            ArrayList<Reply<R>> answer = new ArrayList<>(input.size());
            for (int i = 0; i < input.size(); i++) {
                Pending<R> pending = input.get(i);
                answer.add(new Reply<>(
                        pending.address(), pending.requestId(), values.get(i)));
            }
            return List.copyOf(answer);
        });
    }

    /**
     * Wait for ALL actor replies (including errors and cancellation) and
     * return complete indexed attribution. Does not manufacture success
     * from a guest panic: source compiler/actor supervisor must preserve
     * untrappable panic before allowing guest-facing allSettled bindings.
     */
    public static <R> OresFuture<List<Settled<R>>> gatherSettled(
            List<? extends Pending<R>> replies) {
        List<Pending<R>> input = snapshot(replies);
        ArrayList<OresFuture<? extends R>> futures = new ArrayList<>(input.size());
        for (Pending<R> item : input) futures.add(item.response());
        OresFuture<List<FutureCombinators.Outcome<R>>> observed =
                FutureCombinators.allSettled(futures);
        return project(observed, values -> {
            ArrayList<Settled<R>> answer = new ArrayList<>(input.size());
            for (int i = 0; i < input.size(); i++) {
                Pending<R> pending = input.get(i);
                FutureCombinators.Outcome<R> settled = values.get(i);
                answer.add(new Settled<>(
                        pending.address(), pending.requestId(),
                        settled.status(), settled.value(), settled.failure()));
            }
            return List.copyOf(answer);
        });
    }

    /**
     * Project only runtime bookkeeping, never execute guest code in this
     * callback. When an observer cancels the projection, detach its
     * aggregate observer; do not revoke any underlying actor producer.
     */
    private static <A, B> OresFuture<B> project(
            OresFuture<A> observed, java.util.function.Function<A, B> projector) {
        AtomicReference<OresFuture.RuntimeWaiterRegistration<?>> waiter =
                new AtomicReference<>();
        OresFuture<B> result = new OresFuture<>(() -> {
            OresFuture.RuntimeWaiterRegistration<?> active = waiter.getAndSet(null);
            if (active != null) active.detach();
            observed.cancel(false);
        });
        OresFuture.RuntimeWaiterRegistration<?> registration =
                observed.whenCompleteRuntimeCancellable((value, failure) -> {
                    try {
                        if (result.isDone()) return;
                        if (failure == null) {
                            result.completeFromRuntime(projector.apply(value));
                        } else if (observed.isCancelled()) {
                            result.cancel(false);
                        } else {
                            result.failFromRuntime(OresFuture.unwrap(failure));
                        }
                    } catch (RuntimeException | Error projectionFailure) {
                        result.failFromRuntime(projectionFailure);
                    } finally {
                        OresFuture.RuntimeWaiterRegistration<?> active =
                                waiter.getAndSet(null);
                        if (active != null) active.detach();
                    }
                });
        waiter.set(registration);
        if (result.isDone() && waiter.compareAndSet(registration, null)) {
            registration.detach();
        }
        return result;
    }
}

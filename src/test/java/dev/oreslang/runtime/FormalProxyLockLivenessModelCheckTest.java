package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bounded liveness model for actor-side rt proxy lock acquisition.
 *
 * <p>A contended proxy lock must not park a reusable actor carrier. The
 * cooperative design registers a lock waiter, returns the carrier, and later
 * resumes through the actor continuation lane after the grant is reserved.</p>
 */
final class FormalProxyLockLivenessModelCheckTest {
    private enum Mode { BLOCKING_ACQUIRE, COOPERATIVE_SUSPEND }
    private enum Task { WAITER, HOLDER_RELEASE, WAITER_CONTINUATION, PEER }
    private enum Owner { HOLDER, RESERVED_WAITER, WAITER, NONE }
    private enum Action {
        DISPATCH,
        WAITER_TRY_ACQUIRE,
        HOLDER_RELEASE,
        CONTINUATION_ENTER,
        PEER_FINISH,
        CANCEL_WAITER
    }

    private record State(
            List<Task> ready,
            Task carrier,
            Owner owner,
            boolean waiterQueued,
            boolean waiterCancelled,
            boolean waiterDone,
            boolean holderDone,
            boolean peerDone) {
        State { ready = List.copyOf(ready); }

        static State initial() {
            return new State(
                    List.of(Task.WAITER, Task.HOLDER_RELEASE, Task.PEER),
                    null,
                    Owner.HOLDER,
                    false,
                    false,
                    false,
                    false,
                    false);
        }
    }

    private record Edge(State from, Action action, State to) {}
    private record Graph(Set<State> states, List<Edge> edges) {}

    @Test
    void blockingAcquisitionHasConcreteCarrierStarvationWitness() {
        State waiterRunning = step(State.initial(), Action.DISPATCH, Mode.BLOCKING_ACQUIRE)
                .orElseThrow();
        State blocked = step(waiterRunning, Action.WAITER_TRY_ACQUIRE, Mode.BLOCKING_ACQUIRE)
                .orElseThrow();

        assertEquals(Task.WAITER, blocked.carrier());
        assertEquals(Owner.HOLDER, blocked.owner());
        assertTrue(blocked.ready().contains(Task.HOLDER_RELEASE));
        assertTrue(enabled(blocked, Mode.BLOCKING_ACQUIRE).isEmpty(),
                "parked waiter retains the only carrier while the lock holder's release is queued");
    }

    @Test
    void cooperativeWaitAlwaysReturnsTheCarrierAndLetsHolderReleaseRun() {
        Graph graph = explore(Mode.COOPERATIVE_SUSPEND);

        for (State state : graph.states()) {
            if (state.waiterQueued() && state.owner() == Owner.HOLDER) {
                assertFalse(state.carrier() == Task.WAITER,
                        "a queued proxy waiter must not retain a physical actor carrier");
            }
        }

        State waiterRunning = step(State.initial(), Action.DISPATCH, Mode.COOPERATIVE_SUSPEND)
                .orElseThrow();
        State suspended = step(waiterRunning, Action.WAITER_TRY_ACQUIRE, Mode.COOPERATIVE_SUSPEND)
                .orElseThrow();
        assertEquals(null, suspended.carrier());
        assertTrue(suspended.waiterQueued());
        assertEquals(Task.HOLDER_RELEASE, suspended.ready().getFirst());
    }

    @Test
    void unlockReservesGrantBeforeContinuationRuns() {
        State s = State.initial();
        s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.WAITER_TRY_ACQUIRE, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.HOLDER_RELEASE, Mode.COOPERATIVE_SUSPEND).orElseThrow();

        assertEquals(Owner.RESERVED_WAITER, s.owner(),
                "unlock must reserve the grant before scheduling guest continuation");
        assertTrue(s.ready().contains(Task.WAITER_CONTINUATION));
        assertTrue(s.ready().contains(Task.PEER));

        while (s.carrier() == null && !s.ready().isEmpty()
                && s.ready().getFirst() == Task.PEER) {
            s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
            s = step(s, Action.PEER_FINISH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        }

        s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.CONTINUATION_ENTER, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        assertEquals(Owner.WAITER, s.owner());
        assertTrue(s.waiterDone());
    }

    @Test
    void cancellationRemovesPendingWaiterAndUnlockDoesNotResurrectIt() {
        State s = State.initial();
        s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.WAITER_TRY_ACQUIRE, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.CANCEL_WAITER, Mode.COOPERATIVE_SUSPEND).orElseThrow();

        assertFalse(s.waiterQueued());
        assertTrue(s.waiterCancelled());

        s = step(s, Action.DISPATCH, Mode.COOPERATIVE_SUSPEND).orElseThrow();
        s = step(s, Action.HOLDER_RELEASE, Mode.COOPERATIVE_SUSPEND).orElseThrow();

        assertEquals(Owner.NONE, s.owner());
        assertFalse(s.ready().contains(Task.WAITER_CONTINUATION),
                "cancelled lock waiters must never be resurrected by unlock");
    }

    private static Graph explore(Mode mode) {
        Set<State> states = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        states.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : enabled(state, mode)) {
                Optional<State> next = step(state, action, mode);
                if (next.isEmpty()) continue;
                State target = next.orElseThrow();
                edges.add(new Edge(state, action, target));
                if (states.add(target)) queue.addLast(target);
            }
        }
        return new Graph(Set.copyOf(states), List.copyOf(edges));
    }

    private static List<Action> enabled(State s, Mode mode) {
        if (mode == Mode.BLOCKING_ACQUIRE
                && s.carrier() == Task.WAITER
                && s.owner() == Owner.HOLDER
                && s.waiterQueued()) {
            return List.of();
        }

        List<Action> out = new ArrayList<>();
        if (s.carrier() == null && !s.ready().isEmpty()) out.add(Action.DISPATCH);
        if (s.carrier() == Task.WAITER && s.owner() == Owner.HOLDER) {
            out.add(Action.WAITER_TRY_ACQUIRE);
        }
        if (s.carrier() == Task.HOLDER_RELEASE && s.owner() == Owner.HOLDER) {
            out.add(Action.HOLDER_RELEASE);
        }
        if (s.carrier() == Task.WAITER_CONTINUATION
                && s.owner() == Owner.RESERVED_WAITER
                && !s.waiterCancelled()) {
            out.add(Action.CONTINUATION_ENTER);
        }
        if (s.carrier() == Task.PEER) out.add(Action.PEER_FINISH);
        if (s.waiterQueued() && !s.waiterCancelled()) out.add(Action.CANCEL_WAITER);
        return List.copyOf(out);
    }

    private static Optional<State> step(State s, Action action, Mode mode) {
        if (!enabled(s, mode).contains(action)) return Optional.empty();

        return switch (action) {
            case DISPATCH -> {
                List<Task> ready = new ArrayList<>(s.ready());
                Task next = ready.removeFirst();
                yield Optional.of(new State(
                        ready, next, s.owner(), s.waiterQueued(), s.waiterCancelled(),
                        s.waiterDone(), s.holderDone(), s.peerDone()));
            }
            case WAITER_TRY_ACQUIRE -> {
                if (mode == Mode.BLOCKING_ACQUIRE) {
                    yield Optional.of(new State(
                            s.ready(), Task.WAITER, Owner.HOLDER, true, false,
                            false, s.holderDone(), s.peerDone()));
                }
                yield Optional.of(new State(
                        s.ready(), null, Owner.HOLDER, true, false,
                        false, s.holderDone(), s.peerDone()));
            }
            case HOLDER_RELEASE -> {
                List<Task> ready = new ArrayList<>(s.ready());
                Owner owner = Owner.NONE;
                boolean queued = s.waiterQueued();
                if (queued && !s.waiterCancelled()) {
                    owner = Owner.RESERVED_WAITER;
                    ready.add(Task.WAITER_CONTINUATION);
                    queued = false;
                }
                yield Optional.of(new State(
                        ready, null, owner, queued, s.waiterCancelled(),
                        s.waiterDone(), true, s.peerDone()));
            }
            case CONTINUATION_ENTER -> Optional.of(new State(
                    s.ready(), null, Owner.WAITER, false, false,
                    true, s.holderDone(), s.peerDone()));
            case PEER_FINISH -> Optional.of(new State(
                    s.ready(), null, s.owner(), s.waiterQueued(), s.waiterCancelled(),
                    s.waiterDone(), s.holderDone(), true));
            case CANCEL_WAITER -> Optional.of(new State(
                    s.ready(), s.carrier(), s.owner(), false, true,
                    s.waiterDone(), s.holderDone(), s.peerDone()));
        };
    }
}

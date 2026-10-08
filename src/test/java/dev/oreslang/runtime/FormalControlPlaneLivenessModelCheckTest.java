package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bounded liveness model for CONTROL-carrier use by ActorGroup Mailman work.
 *
 * <p>The important semantic boundary is not the number of CONTROL threads. A
 * Mailman callback that waits for external work must return its physical
 * carrier. Retaining the carrier while blocked creates a concrete starvation
 * state even though the callback is logically idle.</p>
 *
 * <p>This model intentionally includes the old/blocking design as a
 * counterexample oracle. The cooperative model is the contract a runtime fix
 * must refine before the Mailman starvation gap can be considered closed.</p>
 */
final class FormalControlPlaneLivenessModelCheckTest {
    private enum Mode {
        BLOCKING_CALLBACK,
        COOPERATIVE_SUSPENSION
    }

    private enum Task {
        MAILMAN,
        PEER_CONTROL,
        MAILMAN_CONTINUATION
    }

    private enum Action {
        DISPATCH,
        MAILMAN_WAIT,
        MAILMAN_FINISH,
        PEER_FINISH,
        CONTINUATION_FINISH
    }

    private record State(
            List<Task> ready,
            Task carrier,
            boolean mailmanWaiting,
            boolean mailmanDone,
            boolean peerDone) {

        static State initial() {
            return new State(
                    List.of(Task.MAILMAN, Task.PEER_CONTROL),
                    null,
                    false,
                    false,
                    false);
        }

        State {
            ready = List.copyOf(ready);
        }

        boolean deadlocked() {
            return enabled(Mode.COOPERATIVE_SUSPENSION).isEmpty()
                    && !mailmanDone
                    && !peerDone;
        }

        List<Action> enabled(Mode mode) {
            List<Action> actions = new ArrayList<>();

            if (carrier == null && !ready.isEmpty()) {
                actions.add(Action.DISPATCH);
            }

            if (carrier == Task.MAILMAN) {
                actions.add(Action.MAILMAN_WAIT);
                actions.add(Action.MAILMAN_FINISH);
            } else if (carrier == Task.PEER_CONTROL) {
                actions.add(Action.PEER_FINISH);
            } else if (carrier == Task.MAILMAN_CONTINUATION) {
                actions.add(Action.CONTINUATION_FINISH);
            }

            return List.copyOf(actions);
        }
    }

    private record Edge(State from, Action action, State to) {}

    private record Graph(Set<State> states, List<Edge> edges) {}

    @Test
    void blockingCallbackHasConcreteCarrierStarvationCounterexample() {
        Graph graph = explore(Mode.BLOCKING_CALLBACK);

        Optional<State> witness = graph.states().stream()
                .filter(state ->
                        state.carrier() == Task.MAILMAN
                                && state.mailmanWaiting()
                                && state.ready().contains(Task.PEER_CONTROL)
                                && !state.peerDone())
                .findFirst();

        assertTrue(witness.isPresent(),
                "blocking callback model must retain the carrier while peer CONTROL work is queued");

        State blocked = witness.orElseThrow();
        assertTrue(enabled(blocked, Mode.BLOCKING_CALLBACK).isEmpty(),
                "without an external unblock, the blocked Mailman owns the carrier forever");
        assertFalse(blocked.peerDone());
    }

    @Test
    void cooperativeSuspensionHasNoDeadlockWithQueuedPeerControlWork() {
        Graph graph = explore(Mode.COOPERATIVE_SUSPENSION);

        for (State state : graph.states()) {
            if (!state.peerDone() && state.ready().contains(Task.PEER_CONTROL)) {
                assertTrue(
                        canReachPeerCompletion(state, graph.edges()),
                        () -> "queued peer CONTROL work lost progress: " + state);
            }

            if (state.mailmanWaiting()) {
                assertFalse(
                        state.carrier() == Task.MAILMAN,
                        "a logically waiting Mailman must not retain a CONTROL carrier");
            }
        }
    }

    @Test
    void cooperativeWaitPlacesContinuationBehindAlreadyQueuedPeerWork() {
        State initial = State.initial();
        State mailmanRunning =
                step(initial, Action.DISPATCH, Mode.COOPERATIVE_SUSPENSION).orElseThrow();
        State suspended =
                step(mailmanRunning, Action.MAILMAN_WAIT, Mode.COOPERATIVE_SUSPENSION)
                        .orElseThrow();

        assertTrue(suspended.mailmanWaiting());
        assertTrue(suspended.carrier() == null);
        assertTrue(
                suspended.ready().equals(
                        List.of(Task.PEER_CONTROL, Task.MAILMAN_CONTINUATION)),
                "suspension must yield and requeue the continuation behind existing CONTROL work");

        State peerRunning =
                step(suspended, Action.DISPATCH, Mode.COOPERATIVE_SUSPENSION).orElseThrow();
        assertTrue(peerRunning.carrier() == Task.PEER_CONTROL);
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

    private static List<Action> enabled(State state, Mode mode) {
        if (mode == Mode.BLOCKING_CALLBACK
                && state.carrier() == Task.MAILMAN
                && state.mailmanWaiting()) {
            return List.of();
        }
        return state.enabled(mode);
    }

    private static Optional<State> step(State state, Action action, Mode mode) {
        if (!enabled(state, mode).contains(action)) return Optional.empty();

        return switch (action) {
            case DISPATCH -> {
                if (state.carrier() != null || state.ready().isEmpty()) {
                    yield Optional.empty();
                }
                List<Task> remaining = new ArrayList<>(state.ready());
                Task next = remaining.removeFirst();
                boolean waiting =
                        next == Task.MAILMAN_CONTINUATION
                                ? false
                                : state.mailmanWaiting();
                yield Optional.of(new State(
                        remaining,
                        next,
                        waiting,
                        state.mailmanDone(),
                        state.peerDone()));
            }

            case MAILMAN_WAIT -> {
                if (state.carrier() != Task.MAILMAN) yield Optional.empty();

                if (mode == Mode.BLOCKING_CALLBACK) {
                    yield Optional.of(new State(
                            state.ready(),
                            Task.MAILMAN,
                            true,
                            false,
                            state.peerDone()));
                }

                List<Task> ready = new ArrayList<>(state.ready());
                ready.add(Task.MAILMAN_CONTINUATION);
                yield Optional.of(new State(
                        ready,
                        null,
                        true,
                        false,
                        state.peerDone()));
            }

            case MAILMAN_FINISH -> {
                if (state.carrier() != Task.MAILMAN) yield Optional.empty();
                yield Optional.of(new State(
                        state.ready(),
                        null,
                        false,
                        true,
                        state.peerDone()));
            }

            case PEER_FINISH -> {
                if (state.carrier() != Task.PEER_CONTROL) yield Optional.empty();
                yield Optional.of(new State(
                        state.ready(),
                        null,
                        state.mailmanWaiting(),
                        state.mailmanDone(),
                        true));
            }

            case CONTINUATION_FINISH -> {
                if (state.carrier() != Task.MAILMAN_CONTINUATION) yield Optional.empty();
                yield Optional.of(new State(
                        state.ready(),
                        null,
                        false,
                        true,
                        state.peerDone()));
            }
        };
    }

    private static boolean canReachPeerCompletion(State start, List<Edge> edges) {
        Map<State, List<State>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }

        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            if (state.peerDone()) return true;

            for (State next : adjacency.getOrDefault(state, List.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }

        return false;
    }
}

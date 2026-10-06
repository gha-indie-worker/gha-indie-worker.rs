package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Bounded structured-cancellation model for a root -> child -> grandchild tree.
 *
 * <p>The model captures the runtime contract exercised by
 * ActorStructuredCancellationTest: lifecycle authority flows downward,
 * cancellation requests cascade through the subtree, and parent termination
 * cannot make structured descendants into orphans.</p>
 */
final class FormalStructuredCancellationModelCheckTest {
    private enum NodeState {
        RUNNING,
        STOPPING,
        CANCELLING,
        TERMINATED_OK,
        TERMINATED_CANCELLED
    }

    private enum Action {
        ROOT_STOP,
        ROOT_CANCEL,
        CHILD_STOP,
        CHILD_CANCEL,
        GRANDCHILD_STOP,
        GRANDCHILD_CANCEL,
        GRANDCHILD_TERMINATE,
        CHILD_TERMINATE,
        ROOT_TERMINATE,
        FORCE_KILL_TREE
    }

    private record State(
            NodeState root,
            NodeState child,
            NodeState grandchild) {

        static State initial() {
            return new State(
                    NodeState.RUNNING,
                    NodeState.RUNNING,
                    NodeState.RUNNING);
        }

        boolean stable() {
            return !inProgress(root)
                    && !inProgress(child)
                    && !inProgress(grandchild);
        }
    }

    private record Edge(State from, Action action, State to) {}

    private record Graph(Set<State> states, List<Edge> edges) {}

    @Test
    void structuredTeardownNeverProducesAnOrphanedDescendant() {
        Graph graph = explore();
        assertTrue(graph.states().size() >= 15,
                "three-level supervision model must explore independent and cascading teardown");

        for (State state : graph.states()) {
            assertSafety(state);

            if (!state.stable()) {
                assertTrue(
                        canReachStable(state, graph.edges()),
                        () -> "teardown state lost its completion path: " + state);
            }
        }
    }

    @Test
    void rootStopAndCancellationCascadeBeforeRootMayTerminate() {
        State stopped = step(State.initial(), Action.ROOT_STOP).orElseThrow();
        assertEquals(NodeState.STOPPING, stopped.root());
        assertEquals(NodeState.CANCELLING, stopped.child());
        assertEquals(NodeState.CANCELLING, stopped.grandchild());
        assertTrue(step(stopped, Action.ROOT_TERMINATE).isEmpty(),
                "root cannot terminate before structured descendants");

        State cancelled = step(State.initial(), Action.ROOT_CANCEL).orElseThrow();
        assertEquals(NodeState.CANCELLING, cancelled.root());
        assertEquals(NodeState.CANCELLING, cancelled.child());
        assertEquals(NodeState.CANCELLING, cancelled.grandchild());
    }

    @Test
    void childLifecycleAuthorityDoesNotPropagateUpward() {
        State childCancel = step(State.initial(), Action.CHILD_CANCEL).orElseThrow();
        assertEquals(NodeState.RUNNING, childCancel.root(),
                "child cancellation authority cannot cancel its parent");
        assertEquals(NodeState.CANCELLING, childCancel.child());
        assertEquals(NodeState.CANCELLING, childCancel.grandchild());

        State childStop = step(State.initial(), Action.CHILD_STOP).orElseThrow();
        assertEquals(NodeState.RUNNING, childStop.root());
        assertEquals(NodeState.STOPPING, childStop.child());
        assertEquals(NodeState.CANCELLING, childStop.grandchild());
    }

    @Test
    void forceKillIsAtomicAcrossTheStructuredSubtree() {
        State killed = step(State.initial(), Action.FORCE_KILL_TREE).orElseThrow();

        assertEquals(NodeState.TERMINATED_CANCELLED, killed.root());
        assertEquals(NodeState.TERMINATED_CANCELLED, killed.child());
        assertEquals(NodeState.TERMINATED_CANCELLED, killed.grandchild());
        assertTrue(killed.stable());
    }

    private static Graph explore() {
        Set<State> states = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();

        State initial = State.initial();
        states.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(state, action);
                if (next.isEmpty()) continue;

                State target = next.orElseThrow();
                edges.add(new Edge(state, action, target));
                if (states.add(target)) queue.addLast(target);
            }
        }

        return new Graph(Set.copyOf(states), List.copyOf(edges));
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action) {
            case ROOT_STOP -> {
                if (s.root() != NodeState.RUNNING) yield Optional.empty();
                yield Optional.of(new State(
                        NodeState.STOPPING,
                        requestCancel(s.child()),
                        requestCancel(s.grandchild())));
            }

            case ROOT_CANCEL -> {
                if (terminal(s.root())) yield Optional.empty();
                yield Optional.of(new State(
                        NodeState.CANCELLING,
                        requestCancel(s.child()),
                        requestCancel(s.grandchild())));
            }

            case CHILD_STOP -> {
                if (s.child() != NodeState.RUNNING) yield Optional.empty();
                yield Optional.of(new State(
                        s.root(),
                        NodeState.STOPPING,
                        requestCancel(s.grandchild())));
            }

            case CHILD_CANCEL -> {
                if (terminal(s.child())) yield Optional.empty();
                yield Optional.of(new State(
                        s.root(),
                        NodeState.CANCELLING,
                        requestCancel(s.grandchild())));
            }

            case GRANDCHILD_STOP -> {
                if (s.grandchild() != NodeState.RUNNING) yield Optional.empty();
                yield Optional.of(new State(
                        s.root(),
                        s.child(),
                        NodeState.STOPPING));
            }

            case GRANDCHILD_CANCEL -> {
                if (terminal(s.grandchild())) yield Optional.empty();
                yield Optional.of(new State(
                        s.root(),
                        s.child(),
                        NodeState.CANCELLING));
            }

            case GRANDCHILD_TERMINATE -> {
                if (!inProgress(s.grandchild())) yield Optional.empty();
                yield Optional.of(new State(
                        s.root(),
                        s.child(),
                        terminalFor(s.grandchild())));
            }

            case CHILD_TERMINATE -> {
                if (!inProgress(s.child()) || !terminal(s.grandchild())) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.root(),
                        terminalFor(s.child()),
                        s.grandchild()));
            }

            case ROOT_TERMINATE -> {
                if (!inProgress(s.root())
                        || !terminal(s.child())
                        || !terminal(s.grandchild())) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        terminalFor(s.root()),
                        s.child(),
                        s.grandchild()));
            }

            case FORCE_KILL_TREE -> {
                if (terminal(s.root())
                        && terminal(s.child())
                        && terminal(s.grandchild())) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        NodeState.TERMINATED_CANCELLED,
                        NodeState.TERMINATED_CANCELLED,
                        NodeState.TERMINATED_CANCELLED));
            }
        };
    }

    private static NodeState requestCancel(NodeState state) {
        if (terminal(state)) return state;
        return NodeState.CANCELLING;
    }

    private static NodeState terminalFor(NodeState state) {
        return state == NodeState.STOPPING
                ? NodeState.TERMINATED_OK
                : NodeState.TERMINATED_CANCELLED;
    }

    private static void assertSafety(State state) {
        if (terminal(state.root())) {
            assertTrue(terminal(state.child()));
            assertTrue(terminal(state.grandchild()));
        }

        if (terminal(state.child())) {
            assertTrue(terminal(state.grandchild()));
        }

        if (inProgress(state.root())) {
            assertFalse(state.child() == NodeState.RUNNING,
                    "root teardown must already have fenced/cancelled its child");
            assertFalse(state.grandchild() == NodeState.RUNNING,
                    "root teardown must already have fenced/cancelled all descendants");
        }

        if (inProgress(state.child())) {
            assertFalse(state.grandchild() == NodeState.RUNNING,
                    "child teardown must already have fenced/cancelled its grandchild");
        }
    }

    private static boolean canReachStable(State start, List<Edge> edges) {
        Map<State, List<State>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.action() == Action.FORCE_KILL_TREE) {
                continue;
            }
            adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        }

        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            if (state.stable()) return true;
            for (State next : adjacency.getOrDefault(state, List.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return false;
    }

    private static boolean terminal(NodeState state) {
        return state == NodeState.TERMINATED_OK
                || state == NodeState.TERMINATED_CANCELLED;
    }

    private static boolean inProgress(NodeState state) {
        return state == NodeState.STOPPING
                || state == NodeState.CANCELLING;
    }
}

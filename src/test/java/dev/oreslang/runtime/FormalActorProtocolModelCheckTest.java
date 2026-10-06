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
 * Dependency-free bounded model checking for the actor protocol/Future hand-off
 * contract.
 *
 * <p>This is intentionally not a randomized concurrency test. It exhaustively
 * enumerates every legal transition from the initial state, then checks the
 * safety invariants on every reachable state and weak-progress reachability
 * from every live non-terminal state.</p>
 *
 * <p>The model corresponds to the runtime protocol path:
 * request admission -> actor turn -> synchronous reply OR OresFuture hand-off
 * -> completion notification -> mailbox continuation -> reply. Stop/runtime
 * close may intervene at every boundary.</p>
 */
final class FormalActorProtocolModelCheckTest {
    private enum Phase {
        EMPTY,
        REQUEST_QUEUED,
        REQUEST_RUNNING,
        FUTURE_PENDING,
        CONTINUATION_QUEUED,
        REPLIED,
        CANCELLED
    }

    private enum Action {
        QUEUE_REQUEST,
        START_REQUEST,
        RETURN_SYNC,
        SUSPEND_TO_FUTURE,
        COMPLETE_FUTURE,
        QUEUE_CONTINUATION,
        RUN_CONTINUATION,
        STOP_ACTOR,
        CLOSE_RUNTIME,
        LATE_COMPLETE
    }

    private record State(
            Phase phase,
            boolean stopped,
            boolean runtimeClosed,
            boolean futureCreated,
            boolean futureCompleted,
            int mailboxReservations,
            int guestEntries,
            int replyCompletions,
            int replyCancellations) {

        static State initial() {
            return new State(Phase.EMPTY, false, false, false, false, 0, 0, 0, 0);
        }

        boolean terminal() {
            return stopped
                    || runtimeClosed
                    || phase == Phase.REPLIED
                    || phase == Phase.CANCELLED;
        }

        State withPhase(Phase next) {
            return new State(
                    next,
                    stopped,
                    runtimeClosed,
                    futureCreated,
                    futureCompleted,
                    mailboxReservations,
                    guestEntries,
                    replyCompletions,
                    replyCancellations);
        }
    }

    private record Edge(State from, Action action, State to) {}

    @Test
    void boundedStateSpaceSatisfiesProtocolSafetyAndProgress() {
        Graph graph = explore();

        assertTrue(graph.states().size() >= 20,
                "model must explore a non-trivial state space");

        for (State state : graph.states()) {
            assertSafety(state);
            if (!state.terminal() && !state.stopped() && !state.runtimeClosed()) {
                assertTrue(
                        canReachTerminal(state, graph.edges()),
                        () -> "live state has no progress path to terminal reply: " + state);
            }
        }

        for (Edge edge : graph.edges()) {
            if (edge.action() == Action.COMPLETE_FUTURE
                    || edge.action() == Action.LATE_COMPLETE) {
                assertEquals(
                        edge.from().guestEntries(),
                        edge.to().guestEntries(),
                        "Future completion threads must never execute guest continuation code");
            }
            if (edge.from().terminal()) {
                assertFalse(
                        edge.to().phase() == Phase.REQUEST_RUNNING
                                || edge.to().phase() == Phase.FUTURE_PENDING
                                || edge.to().phase() == Phase.CONTINUATION_QUEUED,
                        "terminal replies may not be resurrected");
            }
        }
    }

    private static Graph explore() {
        Set<State> states = new HashSet<>();
        List<Edge> edges = new ArrayList<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        states.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State current = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(current, action);
                if (next.isEmpty()) continue;

                State target = next.orElseThrow();
                edges.add(new Edge(current, action, target));
                if (states.add(target)) {
                    queue.addLast(target);
                }
            }
        }

        return new Graph(Set.copyOf(states), List.copyOf(edges));
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action) {
            case QUEUE_REQUEST -> {
                if (s.phase() != Phase.EMPTY || s.stopped() || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.REQUEST_QUEUED,
                        false,
                        false,
                        false,
                        false,
                        1,
                        s.guestEntries(),
                        0,
                        0));
            }
            case START_REQUEST -> {
                if (s.phase() != Phase.REQUEST_QUEUED || s.stopped() || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.REQUEST_RUNNING,
                        false,
                        false,
                        s.futureCreated(),
                        s.futureCompleted(),
                        0,
                        s.guestEntries() + 1,
                        s.replyCompletions(),
                        s.replyCancellations()));
            }
            case RETURN_SYNC -> {
                if (s.phase() != Phase.REQUEST_RUNNING || s.stopped() || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.REPLIED,
                        false,
                        false,
                        s.futureCreated(),
                        s.futureCompleted(),
                        0,
                        s.guestEntries(),
                        s.replyCompletions() + 1,
                        s.replyCancellations()));
            }
            case SUSPEND_TO_FUTURE -> {
                if (s.phase() != Phase.REQUEST_RUNNING || s.stopped() || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.FUTURE_PENDING,
                        false,
                        false,
                        true,
                        false,
                        0,
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations()));
            }
            case COMPLETE_FUTURE -> {
                if (s.phase() != Phase.FUTURE_PENDING || s.futureCompleted()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.phase(),
                        s.stopped(),
                        s.runtimeClosed(),
                        s.futureCreated(),
                        true,
                        s.mailboxReservations(),
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations()));
            }
            case QUEUE_CONTINUATION -> {
                if (s.phase() != Phase.FUTURE_PENDING
                        || !s.futureCompleted()
                        || s.stopped()
                        || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.CONTINUATION_QUEUED,
                        false,
                        false,
                        true,
                        true,
                        1,
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations()));
            }
            case RUN_CONTINUATION -> {
                if (s.phase() != Phase.CONTINUATION_QUEUED
                        || s.stopped()
                        || s.runtimeClosed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.REPLIED,
                        false,
                        false,
                        true,
                        true,
                        0,
                        s.guestEntries() + 1,
                        s.replyCompletions() + 1,
                        s.replyCancellations()));
            }
            case STOP_ACTOR -> {
                if (s.stopped()) yield Optional.empty();
                if (s.phase() == Phase.EMPTY) {
                    yield Optional.of(new State(
                            Phase.EMPTY,
                            true,
                            s.runtimeClosed(),
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                if (s.phase() == Phase.REPLIED) {
                    yield Optional.of(new State(
                            Phase.REPLIED,
                            true,
                            s.runtimeClosed(),
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                if (s.phase() == Phase.CANCELLED) {
                    yield Optional.of(new State(
                            Phase.CANCELLED,
                            true,
                            s.runtimeClosed(),
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                yield Optional.of(new State(
                        Phase.CANCELLED,
                        true,
                        s.runtimeClosed(),
                        s.futureCreated(),
                        s.futureCompleted(),
                        0,
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations() + 1));
            }
            case CLOSE_RUNTIME -> {
                if (s.runtimeClosed()) yield Optional.empty();
                if (s.phase() == Phase.EMPTY) {
                    yield Optional.of(new State(
                            Phase.EMPTY,
                            true,
                            true,
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                if (s.phase() == Phase.REPLIED) {
                    yield Optional.of(new State(
                            Phase.REPLIED,
                            true,
                            true,
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                if (s.phase() == Phase.CANCELLED) {
                    yield Optional.of(new State(
                            Phase.CANCELLED,
                            true,
                            true,
                            s.futureCreated(),
                            s.futureCompleted(),
                            0,
                            s.guestEntries(),
                            s.replyCompletions(),
                            s.replyCancellations()));
                }
                yield Optional.of(new State(
                        Phase.CANCELLED,
                        true,
                        true,
                        s.futureCreated(),
                        s.futureCompleted(),
                        0,
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations() + 1));
            }
            case LATE_COMPLETE -> {
                if (!s.terminal() || !s.futureCreated() || s.futureCompleted()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.phase(),
                        s.stopped(),
                        s.runtimeClosed(),
                        true,
                        true,
                        s.mailboxReservations(),
                        s.guestEntries(),
                        s.replyCompletions(),
                        s.replyCancellations()));
            }
        };
    }

    private static void assertSafety(State state) {
        assertTrue(
                state.mailboxReservations() == 0 || state.mailboxReservations() == 1,
                "bounded model may own at most one protocol mailbox reservation");

        boolean queued = state.phase() == Phase.REQUEST_QUEUED
                || state.phase() == Phase.CONTINUATION_QUEUED;
        assertEquals(
                queued ? 1 : 0,
                state.mailboxReservations(),
                "mailbox reservation must exactly match a queued request/continuation");

        assertFalse(state.futureCompleted() && !state.futureCreated(),
                "a Future cannot complete before it exists");

        assertTrue(
                state.replyCompletions() + state.replyCancellations() <= 1,
                "protocol reply must settle at most once");
        assertTrue(state.guestEntries() <= 2,
                "one request turn plus at most one continuation turn");

        if (state.phase() == Phase.REPLIED) {
            assertEquals(1, state.replyCompletions());
            assertEquals(0, state.replyCancellations());
        }
        if (state.phase() == Phase.CANCELLED) {
            assertEquals(0, state.replyCompletions());
            assertEquals(1, state.replyCancellations());
        }
        if (state.stopped() || state.runtimeClosed()) {
            assertTrue(state.terminal(),
                    "stop/close must atomically drain or cancel non-terminal protocol work");
            assertEquals(0, state.mailboxReservations(),
                    "teardown may not leak mailbox reservations");
        }
        if (state.guestEntries() == 2) {
            assertEquals(Phase.REPLIED, state.phase(),
                    "second guest entry is only the mailbox continuation that settles the reply");
        }
    }

    private static boolean canReachTerminal(State start, List<Edge> edges) {
        Map<State, List<State>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.action() == Action.STOP_ACTOR
                    || edge.action() == Action.CLOSE_RUNTIME) {
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
            if (state.terminal()) return true;
            for (State next : adjacency.getOrDefault(state, List.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return false;
    }

    private record Graph(Set<State> states, List<Edge> edges) {}
}

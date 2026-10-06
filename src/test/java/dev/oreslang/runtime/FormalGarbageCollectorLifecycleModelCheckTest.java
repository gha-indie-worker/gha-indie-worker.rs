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
 * Explicit-state model of RuntimeGarbageCollector cleanup/retry/retirement.
 *
 * <p>The model follows the real runtime's important distinction: while the
 * registry is open, failed cleanup remains retryable; context close is
 * best-effort and clears the registry even if a final hook fails.</p>
 */
final class FormalGarbageCollectorLifecycleModelCheckTest {
    private enum Action {
        OWNER_DIES,
        ENQUEUE_REFERENCE,
        RETIRE_ACTOR_DOMAIN,
        EXPLICIT_CLEAN_SUCCESS,
        EXPLICIT_CLEAN_FAILURE,
        SWEEP_SUCCESS,
        SWEEP_FAILURE,
        CLOSE_SUCCESS,
        CLOSE_FAILURE
    }

    private record State(
            boolean tracked,
            boolean ownerReachable,
            boolean queued,
            boolean actorDomainRetired,
            boolean retryable,
            boolean cleaned,
            boolean closed,
            boolean failureBudget,
            int successfulCleanups,
            int cleanupAttempts) {

        static State initial() {
            return new State(
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    0,
                    0);
        }

        boolean sweepEligible() {
            return tracked && (queued || actorDomainRetired || retryable);
        }
    }

    private record Edge(State from, Action action, State to) {}

    private record Graph(Set<State> states, List<Edge> edges) {}

    @Test
    void openRegistryCleanupIsExactlyOnceAndFailuresRemainRetryable() {
        Graph graph = explore();
        assertTrue(graph.states().size() >= 25,
                "GC lifecycle model must exercise success/failure/retirement/close interleavings");

        for (State state : graph.states()) {
            assertSafety(state);

            if (!state.closed() && state.tracked() && state.retryable()) {
                assertTrue(
                        canReachSuccessfulCleanup(state, graph.edges()),
                        () -> "retryable cleanup lost its success path: " + state);
            }

            if (!state.closed() && state.tracked() && state.actorDomainRetired()) {
                assertTrue(
                        canReachSuccessfulCleanup(state, graph.edges()),
                        () -> "retired actor domain must remain deterministically reclaimable: " + state);
            }
        }
    }

    @Test
    void actorRetirementMakesReachableOwnerCleanupEligible() {
        State retired = step(
                State.initial(),
                Action.RETIRE_ACTOR_DOMAIN).orElseThrow();

        assertTrue(retired.ownerReachable(),
                "the stale host owner deliberately remains strongly reachable");
        assertTrue(retired.sweepEligible());

        State cleaned = step(retired, Action.SWEEP_SUCCESS).orElseThrow();
        assertTrue(cleaned.cleaned());
        assertFalse(cleaned.tracked());
        assertEquals(1, cleaned.successfulCleanups());
    }

    @Test
    void consumedReferenceNotificationStillHasRetryPathAfterFailure() {
        State dead = step(State.initial(), Action.OWNER_DIES).orElseThrow();
        State queued = step(dead, Action.ENQUEUE_REFERENCE).orElseThrow();
        State failed = step(queued, Action.SWEEP_FAILURE).orElseThrow();

        assertFalse(failed.queued(),
                "ReferenceQueue notification is one-shot and is consumed by the failed sweep");
        assertTrue(failed.retryable(),
                "failed cleanup must retain an independent retry path");

        State cleaned = step(failed, Action.SWEEP_SUCCESS).orElseThrow();
        assertTrue(cleaned.cleaned());
        assertEquals(1, cleaned.successfulCleanups());
    }

    @Test
    void contextCloseIsExplicitlyBestEffortRatherThanAFalseExactlyOnceClaim() {
        State failedClose = step(State.initial(), Action.CLOSE_FAILURE).orElseThrow();

        assertTrue(failedClose.closed());
        assertFalse(failedClose.tracked(),
                "close clears registry state even when the final host hook fails");
        assertFalse(failedClose.cleaned(),
                "formal contract must not claim a failed best-effort hook succeeded");
        assertEquals(0, failedClose.successfulCleanups());
        assertEquals(1, failedClose.cleanupAttempts());
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
            case OWNER_DIES -> {
                if (!s.tracked() || !s.ownerReachable() || s.closed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        true, false, s.queued(), s.actorDomainRetired(),
                        s.retryable(), s.cleaned(), false, s.failureBudget(),
                        s.successfulCleanups(), s.cleanupAttempts()));
            }

            case ENQUEUE_REFERENCE -> {
                if (!s.tracked() || s.ownerReachable() || s.queued() || s.closed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        true, false, true, s.actorDomainRetired(),
                        s.retryable(), false, false, s.failureBudget(),
                        s.successfulCleanups(), s.cleanupAttempts()));
            }

            case RETIRE_ACTOR_DOMAIN -> {
                if (!s.tracked() || s.actorDomainRetired() || s.closed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        true, s.ownerReachable(), s.queued(), true,
                        s.retryable(), false, false, s.failureBudget(),
                        s.successfulCleanups(), s.cleanupAttempts()));
            }

            case EXPLICIT_CLEAN_SUCCESS -> {
                if (!s.tracked() || s.closed()) yield Optional.empty();
                yield Optional.of(success(s, s.cleanupAttempts() + 1));
            }

            case EXPLICIT_CLEAN_FAILURE -> {
                if (!s.tracked() || s.closed() || !s.failureBudget()) {
                    yield Optional.empty();
                }
                yield Optional.of(failure(s, s.queued(), s.cleanupAttempts() + 1));
            }

            case SWEEP_SUCCESS -> {
                if (s.closed() || !s.sweepEligible()) yield Optional.empty();
                yield Optional.of(success(s, s.cleanupAttempts() + 1));
            }

            case SWEEP_FAILURE -> {
                if (s.closed() || !s.sweepEligible() || !s.failureBudget()) {
                    yield Optional.empty();
                }
                // Polling the queue consumes the only queue notification; retryable
                // state preserves the later success path.
                yield Optional.of(failure(s, false, s.cleanupAttempts() + 1));
            }

            case CLOSE_SUCCESS -> {
                if (s.closed()) yield Optional.empty();
                if (!s.tracked()) {
                    yield Optional.of(new State(
                            false, s.ownerReachable(), false, s.actorDomainRetired(),
                            false, s.cleaned(), true, s.failureBudget(),
                            s.successfulCleanups(), s.cleanupAttempts()));
                }
                yield Optional.of(new State(
                        false, s.ownerReachable(), false, s.actorDomainRetired(),
                        false, true, true, s.failureBudget(),
                        s.successfulCleanups() + 1, s.cleanupAttempts() + 1));
            }

            case CLOSE_FAILURE -> {
                if (s.closed() || !s.tracked() || !s.failureBudget()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        false, s.ownerReachable(), false, s.actorDomainRetired(),
                        false, false, true, false,
                        s.successfulCleanups(), s.cleanupAttempts() + 1));
            }
        };
    }

    private static State success(State s, int attempts) {
        return new State(
                false,
                s.ownerReachable(),
                false,
                s.actorDomainRetired(),
                false,
                true,
                s.closed(),
                s.failureBudget(),
                s.successfulCleanups() + 1,
                attempts);
    }

    private static State failure(State s, boolean queued, int attempts) {
        return new State(
                true,
                s.ownerReachable(),
                queued,
                s.actorDomainRetired(),
                true,
                false,
                false,
                false,
                s.successfulCleanups(),
                attempts);
    }

    private static void assertSafety(State state) {
        assertTrue(state.successfulCleanups() <= 1,
                "cleanup hook may succeed at most once");

        if (state.cleaned()) {
            assertEquals(1, state.successfulCleanups());
            assertFalse(state.tracked());
            assertFalse(state.retryable());
            assertFalse(state.queued());
        }

        if (state.retryable()) {
            assertTrue(state.tracked());
            assertFalse(state.closed());
            assertFalse(state.cleaned());
        }

        if (state.closed()) {
            assertFalse(state.tracked());
            assertFalse(state.retryable());
            assertFalse(state.queued());
        }

        if (!state.tracked()) {
            assertFalse(state.retryable());
            assertFalse(state.queued());
        }
    }

    private static boolean canReachSuccessfulCleanup(State start, List<Edge> edges) {
        Map<State, List<State>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.action() == Action.CLOSE_FAILURE
                    || edge.action() == Action.CLOSE_SUCCESS) {
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
            if (state.cleaned()) return true;
            for (State next : adjacency.getOrDefault(state, List.of())) {
                if (seen.add(next)) queue.addLast(next);
            }
        }
        return false;
    }
}

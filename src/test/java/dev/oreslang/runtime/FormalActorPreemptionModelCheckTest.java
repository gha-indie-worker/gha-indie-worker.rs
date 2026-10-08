package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Bounded exhaustive model for continuation-safe actor preemption.
 *
 * <p>The model deliberately separates the logical actor execution lease from
 * the physical carrier boundary. A preemption request may make a continuation
 * ready, but that continuation cannot run until the prior carrier boundary has
 * exited. Once ready, the continuation has priority over a later user message.
 * Stop drains both lanes without claiming the active carrier was revoked;
 * the carrier must exit before the actor is fully stopped. Completed source
 * effects may never replay.</p>
 */
final class FormalActorPreemptionModelCheckTest {
    private enum Phase { IDLE, RUNNING, HANDOFF, READY, RESUMED, USER_RUNNING, DONE, STOP_REQUESTED, STOPPED }

    private enum Action {
        START_TURN,
        COMPLETE_EFFECT,
        REQUEST_PREEMPTION,
        QUEUE_USER_MESSAGE,
        EXIT_CARRIER,
        RESUME_CONTINUATION,
        RUN_USER_MESSAGE,
        FINISH_RESUMED_TURN,
        FINISH_USER_MESSAGE,
        STOP,
        EXIT_STOPPED_CARRIER
    }

    private record State(
            Phase phase,
            int activeLeases,
            boolean carrierOwned,
            boolean continuationQueued,
            boolean userQueued,
            int completedEffects,
            int continuationEntries,
            int userEntries) {
        static State initial() {
            return new State(Phase.IDLE, 0, false, false, false, 0, 0, 0);
        }

        boolean terminal() {
            return phase == Phase.DONE || phase == Phase.STOPPED;
        }
    }

    @Test
    void boundedStateSpacePreservesPreemptionSafety() {
        Set<State> states = explore();
        assertTrue(states.size() >= 10, "model must explore a non-trivial handoff state space");

        boolean sawHandoffWithUserWaiting = false;
        boolean sawResumedBeforeUser = false;
        boolean sawStopRequestedOnActiveCarrier = false;
        boolean sawStopAfterCarrierExit = false;

        for (State state : states) {
            assertSafety(state);
            assertTrue(canReachTerminalWithoutNewStop(state),
                    () -> "nonterminal state has no eventual completion path: " + state);
            if (state.continuationQueued()) {
                assertTrue(step(state, Action.RUN_USER_MESSAGE).isEmpty(),
                        "user mailbox may not bypass a queued continuation");
            }
            if (state.phase() == Phase.STOP_REQUESTED) {
                assertTrue(step(state, Action.QUEUE_USER_MESSAGE).isEmpty(),
                        "no new messages may enter after stop is requested");
            }
            if (state.phase() == Phase.HANDOFF && state.userQueued()) {
                sawHandoffWithUserWaiting = true;
            }
            if (state.phase() == Phase.RESUMED && state.userQueued()) {
                sawResumedBeforeUser = true;
            }
            if (state.phase() == Phase.STOP_REQUESTED) {
                sawStopRequestedOnActiveCarrier = true;
            }
            if (state.phase() == Phase.STOPPED) {
                sawStopAfterCarrierExit = true;
            }
        }

        assertTrue(sawHandoffWithUserWaiting,
                "model must cover a later mailbox message racing preemption");
        assertTrue(sawResumedBeforeUser,
                "ready continuation must be able to resume before later user work");
        assertTrue(sawStopRequestedOnActiveCarrier,
                "stop must be modeled while the old carrier still holds its lease");
        assertTrue(sawStopAfterCarrierExit,
                "stop must become final after the old carrier exits");
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : Action.values()) {
                Optional<State> next = step(state, action);
                if (next.isPresent() && seen.add(next.orElseThrow())) {
                    queue.addLast(next.orElseThrow());
                }
            }
        }
        return Set.copyOf(seen);
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action) {
            case START_TURN -> {
                // START_TURN models only the original logical actor turn.
                // Once a user message is queued after preemption, it must enter
                // through RUN_USER_MESSAGE so priority/order accounting cannot
                // be bypassed by this initial-admission transition.
                if (s.phase() != Phase.IDLE
                        || s.activeLeases() != 0
                        || s.userQueued()
                        || s.completedEffects() != 0
                        || s.continuationEntries() != 0
                        || s.userEntries() != 0) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.RUNNING, 1, true, false, false,
                        0, 0, 0));
            }
            case COMPLETE_EFFECT -> {
                if (s.phase() != Phase.RUNNING || s.completedEffects() != 0) yield Optional.empty();
                yield Optional.of(new State(
                        Phase.RUNNING, 1, true, false, s.userQueued(),
                        1, s.continuationEntries(), s.userEntries()));
            }
            case REQUEST_PREEMPTION -> {
                if (s.phase() != Phase.RUNNING || s.completedEffects() != 1) yield Optional.empty();
                yield Optional.of(new State(
                        Phase.HANDOFF, 1, true, true, s.userQueued(),
                        1, s.continuationEntries(), s.userEntries()));
            }
            case QUEUE_USER_MESSAGE -> {
                if (s.terminal() || s.phase() == Phase.STOP_REQUESTED
                        || s.userQueued() || s.phase() == Phase.USER_RUNNING
                        || (s.phase() == Phase.IDLE && s.continuationEntries() == 0)) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.phase(), s.activeLeases(), s.carrierOwned(), s.continuationQueued(), true,
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
            case EXIT_CARRIER -> {
                if (s.phase() != Phase.HANDOFF || !s.carrierOwned()) yield Optional.empty();
                yield Optional.of(new State(
                        Phase.READY, 0, false, true, s.userQueued(),
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
            case RESUME_CONTINUATION -> {
                if (s.phase() != Phase.READY || s.carrierOwned() || !s.continuationQueued()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.RESUMED, 1, true, false, s.userQueued(),
                        s.completedEffects(), s.continuationEntries() + 1, s.userEntries()));
            }
            case RUN_USER_MESSAGE -> {
                // User work is forbidden while an immediately-ready preemption
                // continuation exists. This is the priority invariant.
                if (!s.userQueued() || s.continuationQueued() || s.carrierOwned()
                        || s.phase() != Phase.IDLE || s.continuationEntries() != 1) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.USER_RUNNING, 1, true, false, false,
                        s.completedEffects(), s.continuationEntries(), s.userEntries() + 1));
            }
            case FINISH_RESUMED_TURN -> {
                if (s.phase() != Phase.RESUMED || !s.carrierOwned()) yield Optional.empty();
                yield Optional.of(new State(
                        s.userQueued() ? Phase.IDLE : Phase.DONE,
                        0, false, false, s.userQueued(),
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
            case FINISH_USER_MESSAGE -> {
                if (s.phase() != Phase.USER_RUNNING || !s.carrierOwned()) yield Optional.empty();
                yield Optional.of(new State(
                        Phase.DONE, 0, false, false, false,
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
            case STOP -> {
                if (s.phase() == Phase.STOPPED || s.phase() == Phase.STOP_REQUESTED) {
                    yield Optional.empty();
                }
                // Structured stop immediately rejects queued work, but does
                // NOT revoke a running guest/Truffle carrier. Retain the lease
                // until the carrier has actually left its execution boundary.
                yield Optional.of(new State(
                        s.carrierOwned() ? Phase.STOP_REQUESTED : Phase.STOPPED,
                        s.activeLeases(), s.carrierOwned(), false, false,
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
            case EXIT_STOPPED_CARRIER -> {
                if (s.phase() != Phase.STOP_REQUESTED || !s.carrierOwned()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        Phase.STOPPED, 0, false, false, false,
                        s.completedEffects(), s.continuationEntries(), s.userEntries()));
            }
        };
    }

    /**
     * Existential weak progress without counting STOP itself as a shortcut.
     * Scheduling fairness remains an independent runtime obligation.
     */
    private static boolean canReachTerminalWithoutNewStop(State start) {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        seen.add(start);
        queue.addLast(start);
        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            if (state.terminal()) return true;
            for (Action action : Action.values()) {
                if (action == Action.STOP) continue;
                Optional<State> next = step(state, action);
                if (next.isPresent() && seen.add(next.orElseThrow())) {
                    queue.addLast(next.orElseThrow());
                }
            }
        }
        return false;
    }

    private static void assertSafety(State state) {
        assertTrue(state.activeLeases() == 0 || state.activeLeases() == 1,
                "single-writer actor lease is binary");
        assertEquals(state.activeLeases() == 1, state.carrierOwned(),
                "this bounded model never has two carrier boundaries for one actor");
        assertTrue(state.completedEffects() == 0 || state.completedEffects() == 1,
                "completed work before preemption must never replay");
        assertTrue(state.continuationEntries() <= 1,
                "one preemption continuation resumes at most once");
        assertTrue(state.userEntries() <= 1,
                "one queued user message enters at most once");

        if (state.phase() == Phase.READY) {
            assertTrue(state.continuationQueued());
            assertFalse(state.carrierOwned(),
                    "continuation may become runnable only after old carrier release");
        }
        if (state.phase() == Phase.RESUMED) {
            assertFalse(state.continuationQueued());
            assertEquals(1, state.completedEffects(),
                    "resume preserves already-completed source effects without replay");
        }
        if (state.phase() == Phase.USER_RUNNING) {
            assertFalse(state.continuationQueued(),
                    "later user work cannot overtake a ready preemption continuation");
            assertEquals(1, state.continuationEntries(),
                    "later user work may start only after the preempted turn resumed");
        }
        if (state.phase() == Phase.STOP_REQUESTED) {
            assertEquals(1, state.activeLeases(),
                    "structured cancellation may not release an active lease early");
            assertTrue(state.carrierOwned(),
                    "the existing carrier must unwind before finalized stop");
        }
        if (state.phase() == Phase.STOP_REQUESTED || state.phase() == Phase.STOPPED) {
            assertFalse(state.continuationQueued(), "stop drains continuation lane");
            assertFalse(state.userQueued(), "stop drains user mailbox lane");
        }
        if (state.phase() == Phase.STOPPED) {
            assertEquals(0, state.activeLeases());
            assertFalse(state.carrierOwned());
        }
    }
}

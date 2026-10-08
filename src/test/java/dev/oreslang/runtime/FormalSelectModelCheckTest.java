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
 * Explicit-state model for atomic multi-case select arbitration.
 *
 * <p>Three registered cases are sufficient to enumerate all non-empty readiness
 * subsets while still making loser-detachment and cancellation obligations
 * visible. Channel readiness may change after cancellation, but a cancelled
 * registration can never consume a value or acquire a winner.</p>
 */
final class FormalSelectModelCheckTest {
    private static final int CASES = 3;
    private static final int ALL = (1 << CASES) - 1;

    private enum Kind {
        READY,
        ARBITRATE,
        CANCEL_SELECT
    }

    private record Action(Kind kind, int index) {
        static Action ready(int index) {
            return new Action(Kind.READY, index);
        }

        static Action arbitrate(int index) {
            return new Action(Kind.ARBITRATE, index);
        }

        static Action cancel() {
            return new Action(Kind.CANCEL_SELECT, -1);
        }
    }

    private record State(
            int readyMask,
            int cancelledRegistrationMask,
            int consumedMask,
            int winner,
            boolean selectCancelled) {

        static State initial() {
            return new State(0, 0, 0, -1, false);
        }

        boolean settled() {
            return winner >= 0 || selectCancelled;
        }
    }

    @Test
    void everyReachableSelectStatePreservesSingleWinnerAndLoserDetachment() {
        Set<State> states = explore();
        assertTrue(states.size() >= 20,
                "three-case model must explore a non-trivial state space");

        for (State state : states) {
            assertSafety(state);

            if (!state.settled() && state.readyMask() != 0) {
                boolean hasCommit = false;
                for (int index = 0; index < CASES; index++) {
                    if (step(state, Action.arbitrate(index)).isPresent()) {
                        hasCommit = true;
                        break;
                    }
                }
                assertTrue(hasCommit,
                        () -> "ready live select has no legal commit: " + state);
            }
        }
    }

    private static Set<State> explore() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();

            for (int index = 0; index < CASES; index++) {
                for (Action action : new Action[] {
                        Action.ready(index),
                        Action.arbitrate(index)
                }) {
                    Optional<State> next = step(state, action);
                    if (next.isPresent() && seen.add(next.orElseThrow())) {
                        queue.addLast(next.orElseThrow());
                    }
                }
            }

            Optional<State> cancelled = step(state, Action.cancel());
            if (cancelled.isPresent() && seen.add(cancelled.orElseThrow())) {
                queue.addLast(cancelled.orElseThrow());
            }
        }

        return Set.copyOf(seen);
    }

    private static Optional<State> step(State state, Action action) {
        return switch (action.kind()) {
            case READY -> {
                int bit = bit(action.index());
                if ((state.readyMask() & bit) != 0) yield Optional.empty();
                yield Optional.of(new State(
                        state.readyMask() | bit,
                        state.cancelledRegistrationMask(),
                        state.consumedMask(),
                        state.winner(),
                        state.selectCancelled()));
            }
            case ARBITRATE -> {
                if (state.settled()) yield Optional.empty();
                int bit = bit(action.index());
                if ((state.readyMask() & bit) == 0) yield Optional.empty();
                if ((state.cancelledRegistrationMask() & bit) != 0) {
                    yield Optional.empty();
                }

                yield Optional.of(new State(
                        state.readyMask(),
                        ALL & ~bit,
                        bit,
                        action.index(),
                        false));
            }
            case CANCEL_SELECT -> {
                if (state.settled()) yield Optional.empty();
                yield Optional.of(new State(
                        state.readyMask(),
                        ALL,
                        0,
                        -1,
                        true));
            }
        };
    }

    private static void assertSafety(State state) {
        assertEquals(0, state.cancelledRegistrationMask() & ~ALL);
        assertEquals(0, state.readyMask() & ~ALL);
        assertEquals(0, state.consumedMask() & ~ALL);

        assertTrue(Integer.bitCount(state.consumedMask()) <= 1,
                "one select may consume at most one case");
        assertEquals(
                state.consumedMask(),
                state.consumedMask() & state.readyMask(),
                "only a ready case may be consumed");

        if (state.winner() >= 0) {
            int winnerBit = bit(state.winner());
            assertFalse(state.selectCancelled());
            assertEquals(winnerBit, state.consumedMask(),
                    "winner identity and consumed case must agree");
            assertEquals(0, state.cancelledRegistrationMask() & winnerBit,
                    "winning registration remains the committed case");
            assertEquals(
                    ALL & ~winnerBit,
                    state.cancelledRegistrationMask(),
                    "all losing registrations detach atomically");
        } else {
            assertEquals(0, state.consumedMask(),
                    "no winner means no channel value was consumed");
        }

        if (state.selectCancelled()) {
            assertEquals(-1, state.winner());
            assertEquals(ALL, state.cancelledRegistrationMask(),
                    "select cancellation detaches every registration");
            assertEquals(0, state.consumedMask(),
                    "cancellation cannot consume a channel value");
        }
    }

    private static int bit(int index) {
        if (index < 0 || index >= CASES) {
            throw new IllegalArgumentException("invalid case index " + index);
        }
        return 1 << index;
    }
}

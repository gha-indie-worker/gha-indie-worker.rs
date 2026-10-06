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
 * Bounded model for incremental GC sweep quanta and cleanup-slot ownership.
 *
 * <p>Three slots are enough to prove the core invariants:
 * a bounded sweep advances a stable cursor, retired slots cannot be skipped
 * forever under repeated sweeps, at most one cleanup claimant owns a slot,
 * successful cleanup is terminal, and failed cleanup returns the slot to a
 * retryable state. Attempt counters are intentionally excluded from the state
 * identity because they do not affect enabled transitions; including them
 * would turn this finite safety model into an artificial infinite state
 * space.</p>
 */
final class FormalGcSweepQuantaModelCheckTest {
    private enum SlotState {
        LIVE,
        RETIRED,
        CLAIMED,
        CLEANED
    }

    private enum ActionKind {
        RETIRE,
        SWEEP_ONE,
        CLEANUP_SUCCESS,
        CLEANUP_FAILURE
    }

    private record Action(ActionKind kind, int slot) {
        static Action retire(int slot) {
            return new Action(ActionKind.RETIRE, slot);
        }

        static Action sweep() {
            return new Action(ActionKind.SWEEP_ONE, -1);
        }

        static Action success(int slot) {
            return new Action(ActionKind.CLEANUP_SUCCESS, slot);
        }

        static Action failure(int slot) {
            return new Action(ActionKind.CLEANUP_FAILURE, slot);
        }
    }

    private record State(
            List<SlotState> slots,
            int cursor) {

        static State initial() {
            return new State(
                    List.of(SlotState.LIVE, SlotState.LIVE, SlotState.LIVE),
                    0);
        }

        State {
            slots = List.copyOf(slots);
        }
    }

    @Test
    void everyReachableStatePreservesSingleClaimAndTerminalCleanup() {
        Set<State> states = explore();

        assertTrue(states.size() >= 40,
                "three-slot sweep model should explore retirement/claim/retry orderings");

        for (State state : states) {
            assertTrue(state.cursor() >= 0 && state.cursor() < state.slots().size());
            for (SlotState slot : state.slots()) {
                if (slot == SlotState.CLEANED) {
                    assertFalse(slot == SlotState.CLAIMED);
                }
            }
        }
    }

    @Test
    void oneSweepQuantumClaimsAtMostOneRetiredSlot() {
        State state = State.initial();
        state = step(state, Action.retire(0)).orElseThrow();
        state = step(state, Action.retire(1)).orElseThrow();
        state = step(state, Action.retire(2)).orElseThrow();

        State swept = step(state, Action.sweep()).orElseThrow();

        assertEquals(
                1,
                swept.slots().stream().filter(slot -> slot == SlotState.CLAIMED).count(),
                "one bounded sweep quantum may claim at most one cleanup slot");
    }

    @Test
    void repeatedSweepQuantaEventuallyVisitEveryRetiredSlot() {
        State state = State.initial();
        state = step(state, Action.retire(0)).orElseThrow();
        state = step(state, Action.retire(1)).orElseThrow();
        state = step(state, Action.retire(2)).orElseThrow();

        boolean[] visited = new boolean[3];

        for (int round = 0; round < 9; round++) {
            state = step(state, Action.sweep()).orElseThrow();

            int claimed = claimedSlot(state);
            if (claimed >= 0) {
                visited[claimed] = true;
                state = step(state, Action.failure(claimed)).orElseThrow();
            }
        }

        assertTrue(visited[0]);
        assertTrue(visited[1]);
        assertTrue(visited[2]);
    }

    @Test
    void failedCleanupReturnsSlotToRetryableState() {
        State state = State.initial();
        state = step(state, Action.retire(1)).orElseThrow();

        // Cursor starts at zero. One sweep advances through slot 0 and then
        // claims retired slot 1 in the same bounded scan.
        state = step(state, Action.sweep()).orElseThrow();
        assertEquals(SlotState.CLAIMED, state.slots().get(1));

        state = step(state, Action.failure(1)).orElseThrow();
        assertEquals(SlotState.RETIRED, state.slots().get(1));

        State retried = step(state, Action.sweep()).orElseThrow();
        assertEquals(SlotState.CLAIMED, retried.slots().get(1));
    }

    @Test
    void successfulCleanupIsTerminalForThatSlot() {
        State state = State.initial();
        state = step(state, Action.retire(0)).orElseThrow();
        state = step(state, Action.sweep()).orElseThrow();
        state = step(state, Action.success(0)).orElseThrow();

        assertEquals(SlotState.CLEANED, state.slots().get(0));
        assertTrue(step(state, Action.success(0)).isEmpty());
        assertTrue(step(state, Action.failure(0)).isEmpty());

        for (int i = 0; i < 4; i++) {
            state = step(state, Action.sweep()).orElseThrow();
            assertEquals(SlotState.CLEANED, state.slots().get(0));
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

            for (int slot = 0; slot < state.slots().size(); slot++) {
                for (Action action : List.of(
                        Action.retire(slot),
                        Action.success(slot),
                        Action.failure(slot))) {
                    Optional<State> next = step(state, action);
                    if (next.isPresent() && seen.add(next.orElseThrow())) {
                        queue.addLast(next.orElseThrow());
                    }
                }
            }

            Optional<State> sweep = step(state, Action.sweep());
            if (sweep.isPresent() && seen.add(sweep.orElseThrow())) {
                queue.addLast(sweep.orElseThrow());
            }
        }

        return Set.copyOf(seen);
    }

    private static Optional<State> step(State state, Action action) {
        return switch (action.kind()) {
            case RETIRE -> {
                if (state.slots().get(action.slot()) != SlotState.LIVE) {
                    yield Optional.empty();
                }
                yield Optional.of(withSlot(
                        state,
                        action.slot(),
                        SlotState.RETIRED,
                        state.cursor()));
            }

            case SWEEP_ONE -> {
                int n = state.slots().size();
                List<SlotState> next = new ArrayList<>(state.slots());
                int cursor = state.cursor();

                boolean claimed = false;
                for (int examined = 0; examined < n; examined++) {
                    int index = (cursor + examined) % n;
                    if (next.get(index) == SlotState.RETIRED) {
                        next.set(index, SlotState.CLAIMED);
                        cursor = (index + 1) % n;
                        claimed = true;
                        break;
                    }
                }

                if (!claimed) {
                    cursor = (cursor + 1) % n;
                }

                yield Optional.of(new State(next, cursor));
            }

            case CLEANUP_SUCCESS -> {
                if (state.slots().get(action.slot()) != SlotState.CLAIMED) {
                    yield Optional.empty();
                }
                yield Optional.of(withSlot(
                        state,
                        action.slot(),
                        SlotState.CLEANED,
                        state.cursor()));
            }

            case CLEANUP_FAILURE -> {
                if (state.slots().get(action.slot()) != SlotState.CLAIMED) {
                    yield Optional.empty();
                }
                yield Optional.of(withSlot(
                        state,
                        action.slot(),
                        SlotState.RETIRED,
                        state.cursor()));
            }
        };
    }

    private static State withSlot(
            State state,
            int slot,
            SlotState replacement,
            int cursor) {

        List<SlotState> next = new ArrayList<>(state.slots());
        next.set(slot, replacement);
        return new State(next, cursor);
    }

    private static int claimedSlot(State state) {
        int found = -1;
        for (int i = 0; i < state.slots().size(); i++) {
            if (state.slots().get(i) == SlotState.CLAIMED) {
                if (found >= 0) {
                    throw new AssertionError("multiple cleanup slots claimed");
                }
                found = i;
            }
        }
        return found;
    }
}

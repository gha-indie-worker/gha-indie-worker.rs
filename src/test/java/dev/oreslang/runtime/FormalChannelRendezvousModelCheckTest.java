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
 * Explicit-state model for a zero-capacity Channel rendezvous.
 *
 * <p>The observable contract is atomic: the second side to arrive either pairs
 * with the pending opposite waiter immediately, or installs its own waiter.
 * Cancellation withdraws a waiter before later traffic can match it. Closing a
 * channel fails every still-pending waiter and forbids later admission.</p>
 */
final class FormalChannelRendezvousModelCheckTest {
    private enum OperationState {
        ABSENT,
        PENDING,
        CANCELLED,
        COMPLETED,
        FAILED
    }

    private enum Action {
        REGISTER_READ,
        REGISTER_WRITE,
        CANCEL_READ,
        CANCEL_WRITE,
        CLOSE
    }

    private record State(
            OperationState read,
            OperationState write,
            boolean closed,
            boolean delivered) {

        static State initial() {
            return new State(
                    OperationState.ABSENT,
                    OperationState.ABSENT,
                    false,
                    false);
        }

        boolean terminal() {
            return closed
                    || delivered
                    || (settled(read) && settled(write));
        }
    }

    @Test
    void everyRendezvousInterleavingPreservesExactlyOneHandoff() {
        Set<State> states = explore();
        assertTrue(states.size() >= 12,
                "rendezvous model must explore registration/cancellation/close orderings");

        for (State state : states) {
            assertSafety(state);
        }
    }

    @Test
    void readThenWriteAndWriteThenReadAreObservationallyEquivalent() {
        State readFirst =
                step(State.initial(), Action.REGISTER_READ).orElseThrow();
        State readThenWrite =
                step(readFirst, Action.REGISTER_WRITE).orElseThrow();

        State writeFirst =
                step(State.initial(), Action.REGISTER_WRITE).orElseThrow();
        State writeThenRead =
                step(writeFirst, Action.REGISTER_READ).orElseThrow();

        assertEquals(readThenWrite, writeThenRead);
        assertTrue(readThenWrite.delivered());
        assertEquals(OperationState.COMPLETED, readThenWrite.read());
        assertEquals(OperationState.COMPLETED, readThenWrite.write());
    }

    @Test
    void cancelledWaiterCannotConsumeLaterTraffic() {
        State readPending =
                step(State.initial(), Action.REGISTER_READ).orElseThrow();
        State readCancelled =
                step(readPending, Action.CANCEL_READ).orElseThrow();
        State laterWrite =
                step(readCancelled, Action.REGISTER_WRITE).orElseThrow();

        assertFalse(laterWrite.delivered());
        assertEquals(OperationState.CANCELLED, laterWrite.read());
        assertEquals(OperationState.PENDING, laterWrite.write());

        State closed = step(laterWrite, Action.CLOSE).orElseThrow();
        assertEquals(OperationState.CANCELLED, closed.read());
        assertEquals(OperationState.FAILED, closed.write());
    }

    @Test
    void closeFailsPendingWaitersWithoutInventingDelivery() {
        State readPending =
                step(State.initial(), Action.REGISTER_READ).orElseThrow();
        State closed = step(readPending, Action.CLOSE).orElseThrow();

        assertTrue(closed.closed());
        assertEquals(OperationState.FAILED, closed.read());
        assertEquals(OperationState.ABSENT, closed.write());
        assertFalse(closed.delivered());
        assertTrue(step(closed, Action.REGISTER_WRITE).isEmpty());
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
            case REGISTER_READ -> {
                if (s.closed() || s.read() != OperationState.ABSENT) {
                    yield Optional.empty();
                }

                if (s.write() == OperationState.PENDING) {
                    yield Optional.of(new State(
                            OperationState.COMPLETED,
                            OperationState.COMPLETED,
                            false,
                            true));
                }

                yield Optional.of(new State(
                        OperationState.PENDING,
                        s.write(),
                        false,
                        false));
            }

            case REGISTER_WRITE -> {
                if (s.closed() || s.write() != OperationState.ABSENT) {
                    yield Optional.empty();
                }

                if (s.read() == OperationState.PENDING) {
                    yield Optional.of(new State(
                            OperationState.COMPLETED,
                            OperationState.COMPLETED,
                            false,
                            true));
                }

                yield Optional.of(new State(
                        s.read(),
                        OperationState.PENDING,
                        false,
                        false));
            }

            case CANCEL_READ -> {
                if (s.read() != OperationState.PENDING || s.closed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        OperationState.CANCELLED,
                        s.write(),
                        false,
                        false));
            }

            case CANCEL_WRITE -> {
                if (s.write() != OperationState.PENDING || s.closed()) {
                    yield Optional.empty();
                }
                yield Optional.of(new State(
                        s.read(),
                        OperationState.CANCELLED,
                        false,
                        false));
            }

            case CLOSE -> {
                if (s.closed()) yield Optional.empty();
                yield Optional.of(new State(
                        failPending(s.read()),
                        failPending(s.write()),
                        true,
                        s.delivered()));
            }
        };
    }

    private static OperationState failPending(OperationState state) {
        return state == OperationState.PENDING
                ? OperationState.FAILED
                : state;
    }

    private static void assertSafety(State state) {
        assertEquals(
                state.read() == OperationState.COMPLETED,
                state.write() == OperationState.COMPLETED,
                "a rendezvous can complete only as a read/write pair");

        assertEquals(
                state.delivered(),
                state.read() == OperationState.COMPLETED
                        && state.write() == OperationState.COMPLETED,
                "delivery identity is exactly the committed rendezvous pair");

        if (state.closed()) {
            assertFalse(state.read() == OperationState.PENDING);
            assertFalse(state.write() == OperationState.PENDING);
        }

        if (state.read() == OperationState.CANCELLED
                || state.write() == OperationState.CANCELLED) {
            assertFalse(state.delivered(),
                    "cancelled waiter must never participate in later handoff");
        }

        assertFalse(
                state.read() == OperationState.PENDING
                        && state.write() == OperationState.PENDING,
                "the second arrival must atomically rendezvous rather than leave two waiters pending");
    }

    private static boolean settled(OperationState state) {
        return state == OperationState.CANCELLED
                || state == OperationState.COMPLETED
                || state == OperationState.FAILED;
    }
}

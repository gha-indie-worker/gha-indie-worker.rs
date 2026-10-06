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
 * Explicit-state model for a capacity-two buffered Channel.
 *
 * <p>Three distinct messages are enough to exhaustively prove bounded capacity,
 * nonblocking write rejection, retry after capacity becomes available, FIFO
 * preservation, and post-close draining of already-buffered values.</p>
 */
final class FormalBufferedChannelModelCheckTest {
    private enum Message {
        A,
        B,
        C
    }

    private enum ActionKind {
        WRITE,
        READ,
        CLOSE
    }

    private record Action(ActionKind kind, Message message) {
        static Action write(Message message) {
            return new Action(ActionKind.WRITE, message);
        }

        static Action read() {
            return new Action(ActionKind.READ, null);
        }

        static Action close() {
            return new Action(ActionKind.CLOSE, null);
        }
    }

    private record State(
            List<Message> admitted,
            List<Message> buffer,
            List<Message> delivered,
            boolean closed) {

        static State initial() {
            return new State(List.of(), List.of(), List.of(), false);
        }

        State {
            admitted = List.copyOf(admitted);
            buffer = List.copyOf(buffer);
            delivered = List.copyOf(delivered);
        }
    }

    @Test
    void everyCapacityTwoInterleavingPreservesBoundAndFifo() {
        Set<State> states = explore();

        assertTrue(states.size() >= 80,
                "three-message capacity-two model must explore a non-trivial state space");

        for (State state : states) {
            assertSafety(state);

            if (!state.buffer().isEmpty()) {
                assertTrue(step(state, Action.read()).isPresent(),
                        "buffered data must remain FIFO-readable before and after close");
            }

            if (!state.closed()
                    && state.buffer().size() < 2
                    && state.admitted().size() < Message.values().length) {
                boolean someWriteEnabled = false;
                for (Message message : Message.values()) {
                    if (step(state, Action.write(message)).isPresent()) {
                        someWriteEnabled = true;
                        break;
                    }
                }
                assertTrue(someWriteEnabled,
                        "available capacity plus an unseen message must permit some write");
            }
        }
    }

    @Test
    void fullBufferRejectsThirdWriteWithoutChangingStateAndRetryCanLaterSucceed() {
        State state = State.initial();
        state = step(state, Action.write(Message.A)).orElseThrow();
        state = step(state, Action.write(Message.B)).orElseThrow();

        assertTrue(step(state, Action.write(Message.C)).isEmpty(),
                "nonblocking write must reject rather than overfill capacity");

        state = step(state, Action.read()).orElseThrow();
        State retried = step(state, Action.write(Message.C)).orElseThrow();

        assertEquals(List.of(Message.B, Message.C), retried.buffer());
        assertEquals(List.of(Message.A), retried.delivered());
        assertEquals(List.of(Message.A, Message.B, Message.C), retried.admitted());
    }

    @Test
    void closeFencesWritesButPreservesBufferedFifoDrain() {
        State buffered =
                step(State.initial(), Action.write(Message.A)).orElseThrow();
        State closed = step(buffered, Action.close()).orElseThrow();

        assertTrue(closed.closed());
        assertTrue(step(closed, Action.write(Message.B)).isEmpty(),
                "close is a one-way write/admission fence");
        assertEquals(List.of(Message.A), closed.buffer(),
                "close itself does not fabricate consumption");

        State drained = step(closed, Action.read()).orElseThrow();
        assertEquals(List.of(Message.A), drained.delivered());
        assertTrue(drained.buffer().isEmpty());
        assertTrue(drained.closed());
        assertTrue(step(drained, Action.read()).isEmpty(),
                "closed-empty read is terminal after preserved buffered data drains");
    }

    private static Set<State> explore() {
        List<Action> actions = new ArrayList<>();
        for (Message message : Message.values()) actions.add(Action.write(message));
        actions.add(Action.read());
        actions.add(Action.close());

        Set<State> seen = new HashSet<>();
        ArrayDeque<State> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(initial);

        while (!queue.isEmpty()) {
            State state = queue.removeFirst();
            for (Action action : actions) {
                Optional<State> next = step(state, action);
                if (next.isPresent() && seen.add(next.orElseThrow())) {
                    queue.addLast(next.orElseThrow());
                }
            }
        }

        return Set.copyOf(seen);
    }

    private static Optional<State> step(State s, Action action) {
        return switch (action.kind()) {
            case WRITE -> {
                if (s.closed()
                        || s.admitted().contains(action.message())
                        || s.buffer().size() >= 2) {
                    yield Optional.empty();
                }

                List<Message> admitted = new ArrayList<>(s.admitted());
                admitted.add(action.message());
                List<Message> buffer = new ArrayList<>(s.buffer());
                buffer.add(action.message());

                yield Optional.of(new State(
                        admitted,
                        buffer,
                        s.delivered(),
                        false));
            }

            case READ -> {
                if (s.buffer().isEmpty()) {
                    yield Optional.empty();
                }

                List<Message> buffer = new ArrayList<>(s.buffer());
                Message value = buffer.removeFirst();
                List<Message> delivered = new ArrayList<>(s.delivered());
                delivered.add(value);

                yield Optional.of(new State(
                        s.admitted(),
                        buffer,
                        delivered,
                        s.closed()));
            }

            case CLOSE -> {
                if (s.closed()) yield Optional.empty();
                yield Optional.of(new State(
                        s.admitted(),
                        s.buffer(),
                        s.delivered(),
                        true));
            }
        };
    }

    private static void assertSafety(State state) {
        assertTrue(state.buffer().size() <= 2,
                "buffer capacity is a hard invariant");

        Set<Message> unique = new HashSet<>(state.admitted());
        assertEquals(state.admitted().size(), unique.size(),
                "each modeled write identity can be admitted at most once");

        List<Message> reconstructed = new ArrayList<>(state.delivered());
        reconstructed.addAll(state.buffer());
        assertEquals(state.admitted(), reconstructed,
                "FIFO conservation: admitted sequence equals delivered prefix plus buffered suffix");

        assertEquals(
                state.admitted().size(),
                state.delivered().size() + state.buffer().size(),
                "no message may be duplicated or lost inside the open-state machine");

        if (state.closed()) {
            for (Message message : Message.values()) {
                assertFalse(step(state, Action.write(message)).isPresent());
            }
            assertEquals(
                    !state.buffer().isEmpty(),
                    step(state, Action.read()).isPresent(),
                    "closed channels drain buffered data and reject only once empty");
        }
    }
}

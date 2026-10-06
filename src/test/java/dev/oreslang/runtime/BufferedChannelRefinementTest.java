package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * Trace-refinement test connecting the bounded buffered-channel model to the
 * concrete ChannelRuntime implementation.
 *
 * <p>Every reachable model state is replayed from a fresh concrete capacity-two
 * channel. The test then probes the next observable operation, including the
 * distinction between an open/full nonblocking write (false) and a closed write
 * (exception), plus post-close draining of buffered values.</p>
 */
final class BufferedChannelRefinementTest {
    private enum Message { A, B, C }
    private enum Kind { WRITE, READ, CLOSE }

    private record Action(Kind kind, Message message) {
        static Action write(Message message) { return new Action(Kind.WRITE, message); }
        static Action read() { return new Action(Kind.READ, null); }
        static Action close() { return new Action(Kind.CLOSE, null); }
    }

    private record State(
            List<Message> admitted,
            List<Message> buffer,
            List<Message> delivered,
            boolean closed) {
        State {
            admitted = List.copyOf(admitted);
            buffer = List.copyOf(buffer);
            delivered = List.copyOf(delivered);
        }

        static State initial() {
            return new State(List.of(), List.of(), List.of(), false);
        }
    }

    private record Node(State state, List<Action> trace) {
        Node {
            trace = List.copyOf(trace);
        }
    }

    private record Replay(
            ChannelRuntime.Channel<Message> channel,
            List<Message> delivered) { }

    @Test
    void everyReachableModelStateRefinesToConcreteBufferedChannelBehavior() {
        Set<State> seen = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        State initial = State.initial();
        seen.add(initial);
        queue.add(new Node(initial, List.of()));

        while (!queue.isEmpty()) {
            Node node = queue.removeFirst();
            assertConcreteStateAndNextOperations(node);

            for (Action action : actions()) {
                Optional<State> next = step(node.state(), action);
                if (next.isEmpty()) continue;

                State target = next.orElseThrow();
                if (seen.add(target)) {
                    ArrayList<Action> trace = new ArrayList<>(node.trace());
                    trace.add(action);
                    queue.addLast(new Node(target, trace));
                }
            }
        }

        assertTrue(
                seen.size() >= 80,
                "refinement must cover the same non-trivial three-message state space");
    }

    @Test
    void closeFailsPendingBufferedWriterButPreservesCommittedFifoData() {
        ChannelRuntime.Channel<Message> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryWrite(Message.A));
        assertTrue(channel.tryWrite(Message.B));

        OresFuture<Void> pending = channel.writeAsync(Message.C);
        assertFalse(pending.isDone());

        channel.close();

        CompletionException writeFailure = assertThrows(
                CompletionException.class,
                pending::join);
        assertInstanceOf(
                ChannelRuntime.ChannelClosedException.class,
                writeFailure.getCause());

        assertEquals(Message.A, channel.tryRead().orElseThrow());
        assertEquals(Message.B, channel.tryRead().orElseThrow());

        CompletionException readFailure = assertThrows(
                CompletionException.class,
                channel::tryRead);
        assertInstanceOf(
                ChannelRuntime.ChannelClosedException.class,
                readFailure.getCause());
    }

    @Test
    void cancelledPendingBufferedWriterCannotDeliverAfterCapacityFrees() {
        ChannelRuntime.Channel<Message> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(Message.A));

        OresFuture<Void> pending = channel.writeAsync(Message.B);
        assertFalse(pending.isDone());
        assertTrue(pending.cancel(false));

        assertEquals(Message.A, channel.tryRead().orElseThrow());
        assertTrue(
                channel.tryRead().isEmpty(),
                "cancelled pending writer must be detached before later capacity becomes available");
    }

    private static void assertConcreteStateAndNextOperations(Node node) {
        Replay baseline = replay(node.trace());
        State state = node.state();

        assertEquals(state.closed(), baseline.channel().isClosed(), node::toString);
        assertEquals(state.buffer().size(), baseline.channel().size(), node::toString);
        assertEquals(state.delivered(), baseline.delivered(), node::toString);

        for (Message message : Message.values()) {
            if (state.admitted().contains(message)) continue;

            Replay probe = replay(node.trace());
            if (state.closed()) {
                CompletionException failure = assertThrows(
                        CompletionException.class,
                        () -> probe.channel().tryWrite(message),
                        node::toString);
                assertInstanceOf(
                        ChannelRuntime.ChannelClosedException.class,
                        failure.getCause(),
                        node::toString);
            } else if (state.buffer().size() >= 2) {
                assertFalse(probe.channel().tryWrite(message), node::toString);
                assertEquals(state.buffer().size(), probe.channel().size(), node::toString);
            } else {
                assertTrue(probe.channel().tryWrite(message), node::toString);
                assertEquals(state.buffer().size() + 1, probe.channel().size(), node::toString);
            }
        }

        Replay readProbe = replay(node.trace());
        if (!state.buffer().isEmpty()) {
            Message expected = state.buffer().getFirst();
            assertEquals(expected, readProbe.channel().tryRead().orElseThrow(), node::toString);
            assertEquals(state.buffer().size() - 1, readProbe.channel().size(), node::toString);
        } else if (state.closed()) {
            CompletionException failure = assertThrows(
                    CompletionException.class,
                    readProbe.channel()::tryRead,
                    node::toString);
            assertInstanceOf(
                    ChannelRuntime.ChannelClosedException.class,
                    failure.getCause(),
                    node::toString);
        } else {
            assertTrue(readProbe.channel().tryRead().isEmpty(), node::toString);
        }

        Replay closeProbe = replay(node.trace());
        int before = closeProbe.channel().size();
        closeProbe.channel().close();
        closeProbe.channel().close();
        assertTrue(closeProbe.channel().isClosed(), node::toString);
        assertEquals(
                before,
                closeProbe.channel().size(),
                "close is idempotent and does not consume buffered data: " + node);
    }

    private static Replay replay(List<Action> trace) {
        ChannelRuntime.Channel<Message> channel = new ChannelRuntime.Channel<>(2);
        ArrayList<Message> delivered = new ArrayList<>();

        for (Action action : trace) {
            switch (action.kind()) {
                case WRITE -> assertTrue(
                        channel.tryWrite(action.message()),
                        "model-committed write must commit in concrete replay");
                case READ -> delivered.add(channel.tryRead().orElseThrow());
                case CLOSE -> channel.close();
            }
        }

        return new Replay(channel, List.copyOf(delivered));
    }

    private static List<Action> actions() {
        return List.of(
                Action.write(Message.A),
                Action.write(Message.B),
                Action.write(Message.C),
                Action.read(),
                Action.close());
    }

    private static Optional<State> step(State state, Action action) {
        return switch (action.kind()) {
            case WRITE -> {
                if (state.closed()
                        || state.admitted().contains(action.message())
                        || state.buffer().size() >= 2) {
                    yield Optional.empty();
                }

                ArrayList<Message> admitted = new ArrayList<>(state.admitted());
                admitted.add(action.message());
                ArrayList<Message> buffer = new ArrayList<>(state.buffer());
                buffer.add(action.message());
                yield Optional.of(new State(
                        admitted,
                        buffer,
                        state.delivered(),
                        false));
            }

            case READ -> {
                if (state.buffer().isEmpty()) yield Optional.empty();

                ArrayList<Message> buffer = new ArrayList<>(state.buffer());
                Message value = buffer.removeFirst();
                ArrayList<Message> delivered = new ArrayList<>(state.delivered());
                delivered.add(value);
                yield Optional.of(new State(
                        state.admitted(),
                        buffer,
                        delivered,
                        state.closed()));
            }

            case CLOSE -> {
                if (state.closed()) yield Optional.empty();
                yield Optional.of(new State(
                        state.admitted(),
                        state.buffer(),
                        state.delivered(),
                        true));
            }
        };
    }
}

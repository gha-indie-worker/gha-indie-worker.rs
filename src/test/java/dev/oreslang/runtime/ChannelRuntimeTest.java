package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

final class ChannelRuntimeTest {
    @Test
    void pendingWriterCannotOvertakeBufferedValue() {
        ChannelRuntime.Channel<Integer> channel = new ChannelRuntime.Channel<>(1);
        assertTrue(channel.tryWrite(1));
        OresFuture<Void> pending = channel.writeAsync(2);
        assertFalse(pending.isDone());
        assertEquals(1, channel.tryRead().orElseThrow());
        assertTrue(pending.isDone());
        assertEquals(2, channel.tryRead().orElseThrow());
    }

    @Test
    void shutdownDrainReleasesBufferedValuesAndStopsAtClosedEmptyChannel() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);
        assertTrue(channel.tryWrite("one"));
        assertTrue(channel.tryWrite("two"));
        channel.close();
        java.util.List<String> drained = new java.util.ArrayList<>();
        assertTrue(channel.drainOne(drained::add));
        assertTrue(channel.drainOne(drained::add));
        assertFalse(channel.drainOne(drained::add));
        assertEquals(java.util.List.of("one", "two"), drained);
    }

    @Test
    void bufferedChannelIsFifo() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(2);

        assertTrue(channel.tryWrite("a"));
        assertTrue(channel.tryWrite("b"));
        assertFalse(channel.tryWrite("c"));

        assertEquals("a", channel.tryRead().orElseThrow());
        assertEquals("b", channel.tryRead().orElseThrow());
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void cancelledReadDoesNotConsumeLaterValue() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(1);
        OresFuture<String> pending = channel.readAsync();

        assertTrue(pending.cancel(false));
        assertTrue(pending.isCancelled());
        assertThrows(CancellationException.class, pending::join);

        assertTrue(channel.tryWrite("kept"));
        assertEquals("kept", channel.tryRead().orElseThrow());
    }

    @Test
    void cancelledWriteDoesNotDeliverLater() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<Void> pending = channel.writeAsync("discarded");

        assertTrue(pending.cancel(false));
        assertThrows(CancellationException.class, pending::join);

        OresFuture<String> read = channel.readAsync();
        assertFalse(read.isDone());
        read.cancel(false);
    }

    @Test
    void prioritySelectAlwaysUsesLexicalOrderWhenMultipleCasesAreReady() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        first.tryWrite("one");
        second.tryWrite("two");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));

        ChannelRuntime.SelectResult result =
                set.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join();

        assertEquals(0, result.index());
        assertEquals(ChannelRuntime.SelectOperation.READ, result.operation());
        assertEquals("one", result.value());
        assertEquals("two", second.tryRead().orElseThrow());
    }

    @Test
    void fairSelectRotatesOnAStableSelectSetWithoutRandomness() {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(1);
        first.tryWrite("a1");
        second.tryWrite("b1");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(first),
                ChannelRuntime.read(second));

        ChannelRuntime.SelectResult firstResult =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(0, firstResult.index());

        first.tryWrite("a2");
        ChannelRuntime.SelectResult secondResult =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, secondResult.index());
        assertEquals("b1", secondResult.value());
    }

    @Test
    void nonBlockingSelectRegistrationReturnsPendingFutureAndCompletesLater() {
        ChannelRuntime.Channel<String> incoming = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(incoming));

        OresFuture<ChannelRuntime.SelectResult> pending = set.selectAsync();
        assertFalse(pending.isDone());

        incoming.writeAsync("later").join();

        assertTrue(pending.isDone());
        assertEquals("later", pending.join().value());
    }

    @Test
    void cancellingSelectCannotConsumeAfterCancellationWins() {
        ChannelRuntime.Channel<String> incoming = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(incoming));

        OresFuture<ChannelRuntime.SelectResult> pending = set.selectAsync();
        assertTrue(pending.cancel(false));

        incoming.writeAsync("still-there").join();

        assertThrows(CancellationException.class, pending::join);
        assertEquals("still-there", incoming.tryRead().orElseThrow());
    }

    @Test
    void rendezvousChannelPairsSelectReadWithPendingWrite() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(channel));

        OresFuture<ChannelRuntime.SelectResult> selected = set.selectAsync();
        OresFuture<Void> write = channel.writeAsync("hand-off");

        assertEquals("hand-off", selected.join().value());
        write.join();
    }

    @Test
    void twoPendingSelectsCanRendezvousOnUnbufferedChannel() {
        ChannelRuntime.Channel<Integer> channel =
                new ChannelRuntime.Channel<>(0);

        OresFuture<ChannelRuntime.SelectResult> writer =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.write(channel, 123))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);
        assertFalse(writer.isDone());

        OresFuture<ChannelRuntime.SelectResult> reader =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertEquals(123, reader.join().value());
        assertEquals(
                ChannelRuntime.SelectOperation.WRITE,
                writer.join().operation());
        assertTrue(channel.tryRead().isEmpty());
    }

    @Test
    void selectToSelectRendezvousCommitsExactlyOneArmPerSelection() {
        ChannelRuntime.Channel<Integer> rendezvous =
                new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<Integer> alternate =
                new ChannelRuntime.Channel<>(1);
        alternate.tryWrite(77);

        ChannelRuntime.SelectSet readerSet =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(rendezvous),
                        ChannelRuntime.read(alternate));
        OresFuture<ChannelRuntime.SelectResult> reader =
                readerSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        OresFuture<ChannelRuntime.SelectResult> writer =
                ChannelRuntime.SelectSet.of(
                                ChannelRuntime.write(rendezvous, 99))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        ChannelRuntime.SelectResult result = reader.join();
        if (result.index() == 0) {
            assertEquals(99, result.value());
            assertEquals(77, alternate.tryRead().orElseThrow());
            assertEquals(
                    ChannelRuntime.SelectOperation.WRITE,
                    writer.join().operation());
        } else {
            assertEquals(77, result.value());
            assertFalse(writer.isDone());
            assertTrue(writer.cancel(false));
            assertTrue(rendezvous.tryRead().isEmpty());
        }
    }

    @Test
    void dynamicSelectCanBeBuiltFromListsAndMaps() {
        ChannelRuntime.Channel<String> one = new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> two = new ChannelRuntime.Channel<>(1);
        two.tryWrite("two");

        ChannelRuntime.SelectSet listSet = ChannelRuntime.SelectSet.from(List.of(
                ChannelRuntime.read(one),
                ChannelRuntime.read(two)));
        assertEquals(1,
                listSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().index());

        one.tryWrite("one");
        LinkedHashMap<String, ChannelRuntime.SelectCase> cases = new LinkedHashMap<>();
        cases.put("one", ChannelRuntime.read(one));
        cases.put("two", ChannelRuntime.read(two));
        ChannelRuntime.SelectSet mapSet = ChannelRuntime.SelectSet.fromMap(cases);

        assertEquals(0,
                mapSet.selectAsync(ChannelRuntime.SelectPolicy.PRIORITY).join().index());
    }

    @Test
    void immediateWriteRendezvousWithPendingSelectRead() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.read(channel))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertTrue(channel.tryWrite("direct"));
        assertEquals("direct", selected.join().value());
    }

    @Test
    void immediateReadRendezvousWithPendingSelectWrite() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(
                        ChannelRuntime.write(channel, "direct"))
                        .selectAsync(ChannelRuntime.SelectPolicy.PRIORITY);

        assertEquals("direct", channel.tryRead().orElseThrow());
        assertEquals(
                ChannelRuntime.SelectOperation.WRITE,
                selected.join().operation());
    }

    @Test
    void fairSelectRotatesPastClosedArmAfterTerminalFailure() {
        ChannelRuntime.Channel<String> closed =
                new ChannelRuntime.Channel<>(1);
        ChannelRuntime.Channel<String> ready =
                new ChannelRuntime.Channel<>(1);
        closed.close();
        ready.tryWrite("value");

        ChannelRuntime.SelectSet set = ChannelRuntime.SelectSet.of(
                ChannelRuntime.read(closed),
                ChannelRuntime.read(ready));

        assertThrows(
                RuntimeException.class,
                () -> set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join());

        ChannelRuntime.SelectResult next =
                set.selectAsync(ChannelRuntime.SelectPolicy.FAIR).join();
        assertEquals(1, next.index());
        assertEquals("value", next.value());
    }

    @Test
    void closeFailsPendingWaitersAndSelects() {
        ChannelRuntime.Channel<String> channel = new ChannelRuntime.Channel<>(0);
        OresFuture<String> read = channel.readAsync();
        OresFuture<ChannelRuntime.SelectResult> selected =
                ChannelRuntime.SelectSet.of(ChannelRuntime.read(channel)).selectAsync();

        channel.close();

        assertThrows(RuntimeException.class, read::join);
        assertThrows(RuntimeException.class, selected::join);
    }
}

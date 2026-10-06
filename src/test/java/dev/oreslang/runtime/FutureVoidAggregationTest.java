package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class FutureVoidAggregationTest {
    @Test
    void alreadyCompletedVoidFuturesProduceImmutableNullSlots() throws Exception {
        List<Void> values = OresFutures.<Void>all(List.of(
                OresFuture.<Void>completed(null), OresFuture.<Void>completed(null)))
                .get(2, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(null, null), values);
        assertThrows(UnsupportedOperationException.class, () -> values.add(null));
    }

    @Test
    void pendingRendezvousWritesCompleteAggregateOnlyAfterBothAreRead() throws Exception {
        ChannelRuntime.Channel<String> first = new ChannelRuntime.Channel<>(0);
        ChannelRuntime.Channel<String> second = new ChannelRuntime.Channel<>(0);
        OresFuture<List<Void>> all = OresFutures.all(List.of(
                first.writeAsync("first"), second.writeAsync("second")));
        assertFalse(all.isDone());
        assertEquals("second", second.readAsync().get(2, TimeUnit.SECONDS));
        assertFalse(all.isDone());
        assertEquals("first", first.readAsync().get(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(null, null), all.get(2, TimeUnit.SECONDS));
    }
}

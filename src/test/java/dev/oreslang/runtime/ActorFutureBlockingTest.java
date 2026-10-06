package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorFutureBlockingTest {
    @Test
    void blockingFutureBridgesRejectPendingReadsAndPreserveMailboxProgress() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        CountDownLatch delivered = new CountDownLatch(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var messages = new java.util.concurrent.CopyOnWriteArrayList<Integer>();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 8))) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    if (message == 1) {
                        assertThrows(IllegalStateException.class, pending::get);
                        assertThrows(IllegalStateException.class,
                                () -> pending.get(30, TimeUnit.SECONDS));
                        assertThrows(IllegalStateException.class, pending::join);
                        assertThrows(IllegalStateException.class, () -> AsyncRuntime.await(pending));
                        assertEquals(0, pending.pendingRuntimeWaiterCount(),
                                "rejected blocking reads must not retain continuation waiters");
                    }
                    messages.add(message);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    delivered.countDown();
                }
            });
            actor.send(1);
            actor.send(2);
            try {
                assertTrue(delivered.await(2, TimeUnit.SECONDS),
                        "the single dispatcher must remain available for the next mailbox turn");
                assertNull(failure.get());
                assertEquals(List.of(1, 2), messages);
                assertTrue(actor.isAlive());
                assertTrue(actor.failure().isEmpty());
            } finally {
                // A regression must release a parked carrier so test teardown can finish.
                pending.completeFromRuntime(7);
            }
        }
    }

    @Test
    void settledReadsAndZeroTimeoutPollingRemainAvailableInsideActorTurns() throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        CountDownLatch checked = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawn(() -> (message, context) -> {
                try {
                    var settled = OresFuture.completed(42);
                    assertEquals(42, settled.get());
                    assertEquals(42, settled.get(1, TimeUnit.SECONDS));
                    assertEquals(42, settled.join());
                    assertEquals(42, AsyncRuntime.await(settled));
                    assertThrows(TimeoutException.class, () -> pending.get(0, TimeUnit.NANOSECONDS));
                    assertThrows(IllegalArgumentException.class, () -> pending.get(-1, TimeUnit.NANOSECONDS));
                    assertEquals(0, pending.pendingRuntimeWaiterCount());
                    var original = new IllegalArgumentException("producer failed");
                    assertSame(original, assertThrows(ExecutionException.class,
                            () -> OresFuture.failed(original).get()).getCause());
                    OresFuture<Integer> cancelled = new OresFuture<>();
                    cancelled.cancel(false);
                    assertThrows(CancellationException.class, cancelled::get);
                } catch (Throwable thrown) {
                    failure.set(thrown);
                } finally {
                    checked.countDown();
                }
            });
            actor.send(1);
            assertTrue(checked.await(2, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(actor.isAlive());
        }
    }
}

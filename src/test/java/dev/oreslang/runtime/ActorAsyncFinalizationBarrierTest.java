package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class ActorAsyncFinalizationBarrierTest {

    @Test
    void successfulAsyncInvocationIsNotPublishedUntilTurnExecutorReturns() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            OresFuture<String> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (message, context) -> "done:" + message);

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                    "guest result must stay staged while TurnExecutor still owns the carrier/context");

            releaseCarrier.countDown();

            assertEquals("done:request", result.get(2, TimeUnit.SECONDS));
            assertTrue(result.isDone());
            assertEquals(0, runtime.actorCount(),
                    "one-shot actor must be finalized before its result becomes observable");
        }
    }

    @Test
    void failedAsyncInvocationIsNotPublishedUntilTurnExecutorReturns() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            OresFuture<String> result = runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (message, context) -> {
                        throw new IllegalStateException("boom");
                    });

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone(),
                    "guest failure must stay staged until the actor leaves TurnExecutor");

            releaseCarrier.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertEquals("boom", failure.getCause().getMessage());
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void nestedFutureResultUsesTheSameFinalizationBarrier() throws Exception {
        CountDownLatch guestTurnFinished = new CountDownLatch(1);
        CountDownLatch releaseCarrier = new CountDownLatch(1);

        ActorRuntime.TurnExecutor gated = turn -> {
            turn.run();
            guestTurnFinished.countDown();
            await(releaseCarrier);
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                gated)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            OresFuture<String> result = (OresFuture<String>) (OresFuture) runtime.invokeAsync(
                    ActorRuntime.ActorKind.SHARED,
                    "request",
                    (ActorRuntime.Invocation<String, Object>)
                            (message, context) -> OresFuture.completed("future:" + message));

            assertTrue(guestTurnFinished.await(2, TimeUnit.SECONDS));
            assertFalse(result.isDone());

            releaseCarrier.countDown();

            assertEquals("future:request", result.get(2, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        for (;;) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}

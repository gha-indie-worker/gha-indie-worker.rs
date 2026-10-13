package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorGroupCloseDrainTest {
    // Source actor factories must not capture mutable host objects. The test
    // coordinator is explicit trusted host fixture state, not actor payload.
    private static volatile CountDownLatch signalEntered;
    private static volatile CountDownLatch signalExit;

    @Test
    void closeDoesNotFalselyReportCompletionWhileWorkerTurnRuns() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            signalEntered = entered;
            signalExit = exit;
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        ActorGroupCloseDrainTest.signalEntered.countDown();
                        try {
                            ActorGroupCloseDrainTest.signalExit.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            worker.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> closed;
            try {
                closed = group.closeAsync();
                assertSame(closed, group.closedDone());
                assertFalse(closed.cancel(false),
                        "lifecycle observers must not cancel physical actor shutdown");
                assertFalse(closed.isDone(),
                        "cleared worker registry must not pretend active turn has finalized");
                assertTrue(group.closed());
                assertEquals(0, group.workerCount());
            } finally {
                exit.countDown();
            }
            try {
                closed.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException expectedCancellation) {
                // Structured cancellation is allowed to reject actor.done.
            }
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void emptyGroupCloseFinishesAndRemainsIdempotent() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            OresFuture<Void> done = group.closeAsync();
            assertSame(done, group.closedDone());
            assertTrue(done.isDone());
            assertFalse(done.cancel(false));
            done.get(5, TimeUnit.SECONDS);
            assertSame(done, group.closeAsync());
        }
    }
}

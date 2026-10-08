package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Regression for #401: control headroom is not a borrowable user mailbox slot. */
@Timeout(20)
final class ActorContinuationCapacityTest {
    @Test
    void continuationLaneRejects1025thReservationEvenWithFreeUserHeadroom() throws Exception {
        IsolatePolicy developer = IsolatePolicy.developer();
        IsolatePolicy oneUserSlot = new IsolatePolicy(
                developer.capabilities(), developer.maxHeapBytes(), 1,
                developer.maxWallTime(), developer.adversarial());
        var config = new ActorRuntime.DispatcherConfig(1, 1, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(oneUserSlot, config)) {
            AtomicInteger attempted = new AtomicInteger();
            AtomicInteger taskBodies = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                // The triggering user envelope has already been dequeued and
                // its user-slot reservation released. These continuations must
                // nevertheless stop at the independent 1024-slot control cap.
                for (int i = 0; i < 1025; i++) {
                    context.runtime().startActorTask(resume -> {
                        taskBodies.incrementAndGet();
                        return OresScheduler.done(null);
                    });
                    attempted.incrementAndGet();
                    if (i < 1024) {
                        assertTrue(context.self().isAlive(),
                                "control slot " + (i + 1) + " must be accepted before the boundary");
                    } else {
                        assertFalse(context.self().isAlive(),
                                "the exact 1025th continuation must synchronously fail-stop");
                    }
                }
            });

            ref.send(1);
            assertTrue(ref.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS),
                    "overflow must fail-stop the actor and drain its queued continuations");
            Throwable failure = ref.failure().orElseThrow(
                    () -> new AssertionError("1025th control reservation must be rejected"));
            assertTrue(failure.getMessage().contains("continuation queue overflow"),
                    () -> "unexpected failure: " + failure);
            assertEquals(1025, attempted.get(), "the exact 1025th reservation is the failing boundary");
            assertEquals(0, taskBodies.get(), "no queued source task may run after fail-stop");
        }
    }

    @Test
    void fullContinuationLaneDoesNotConsumeReservedUserMessageCapacity() throws Exception {
        IsolatePolicy developer = IsolatePolicy.developer();
        IsolatePolicy oneUserSlot = new IsolatePolicy(
                developer.capabilities(), developer.maxHeapBytes(), 1,
                developer.maxWallTime(), developer.adversarial());
        var config = new ActorRuntime.DispatcherConfig(1, 1, 64, 16);
        CountDownLatch continuationLaneFull = new CountDownLatch(1);
        CountDownLatch externalUserEnqueued = new CountDownLatch(1);
        AtomicInteger taskBodies = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime(oneUserSlot, config)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    for (int i = 0; i < 1024; i++) {
                        context.runtime().startActorTask(resume -> {
                            taskBodies.incrementAndGet();
                            return OresScheduler.done(null);
                        });
                    }
                    continuationLaneFull.countDown();
                    try {
                        assertTrue(externalUserEnqueued.await(5, TimeUnit.SECONDS),
                                "test did not enqueue the reserved user message");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    context.self().stop();
                } else {
                    fail("queued user work must be discarded after stop");
                }
            });

            ref.send(1);
            assertTrue(continuationLaneFull.await(5, TimeUnit.SECONDS));
            // All 1024 control slots are occupied. This user message must
            // still have its own independent slot and must be admissible.
            try {
                assertDoesNotThrow(() -> ref.send(2));
            } finally {
                externalUserEnqueued.countDown();
            }
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, taskBodies.get());
        }
    }
}

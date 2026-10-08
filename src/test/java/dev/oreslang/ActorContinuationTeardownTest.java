package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorContinuationTeardownTest {
    @Test
    void gracefulStopCancelsQueuedSourceTasksWithoutRunningThem() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int tasks = 256;
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger taskBodies = new AtomicInteger();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                entered.countDown();
                for (int i = 0; i < tasks; i++) {
                    context.runtime().startActorTask(resume -> {
                        taskBodies.incrementAndGet();
                        return OresScheduler.done(null);
                    });
                }
                context.self().stop();
            });

            ref.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty());
            assertEquals(0, taskBodies.get(),
                    "source continuations queued behind a stopped actor must not run");
        }
    }
}

package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresScheduler;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorCooperateOrderingTest {
    @Test
    void repeatedCooperateResumesBeforeLaterActorMessage() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(2, 4, 64, 32);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int cooperates = 256;
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch taskDone = new CountDownLatch(1);
            CountDownLatch messageDone = new CountDownLatch(1);
            AtomicInteger steps = new AtomicInteger();
            AtomicBoolean overtaken = new AtomicBoolean();

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                if (message == 1) {
                    context.runtime().startActorTask(resume -> {
                        int step = steps.getAndIncrement();
                        if (step == 0) started.countDown();
                        if (step < cooperates) return OresScheduler.cooperate();
                        taskDone.countDown();
                        return OresScheduler.done(null);
                    });
                    return;
                }

                if (taskDone.getCount() != 0) overtaken.set(true);
                messageDone.countDown();
                context.self().stop();
            });

            ref.send(1);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            ref.send(2);

            assertTrue(messageDone.await(10, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(overtaken.get());
            assertEquals(cooperates + 1, steps.get());
        }
    }
}

package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
final class ActorAffinityDispatcherTest {
    @Test
    void aBusyLaneDoesNotMigrateItsQueuedWorkAndShutdownReclaimsCapacity() throws Exception {
        var dispatcher = new ActorAffinityDispatcher(2, 1, new int[0], i -> Executors.newSingleThreadExecutor());
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger abandonedRuns = new AtomicInteger();
        Runnable pending = abandonedRuns::incrementAndGet;
        try {
            dispatcher.executeOnLane(0, () -> {
                running.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(running.await(2, TimeUnit.SECONDS));
            dispatcher.executeOnLane(0, pending);
            assertThrows(RejectedExecutionException.class, () -> dispatcher.executeOnLane(1, () -> {}));
            List<Runnable> abandoned = dispatcher.shutdownNow();
            assertEquals(List.of(pending), abandoned);
            assertEquals(0, abandonedRuns.get());
            assertThrows(RejectedExecutionException.class, () -> dispatcher.executeOnLane(1, () -> {}));
            assertTrue(dispatcher.awaitTermination(2, TimeUnit.SECONDS));
        } finally { release.countDown(); dispatcher.shutdownNow(); }
    }

    @Test
    void defaultRuntimeReportsLaneAffinityWithoutClaimingCpuBinding() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.spawnShared(() -> (message, context) -> {});
            try {
                var placement = runtime.placementFor(actor).orElseThrow();
                assertEquals("lane", placement.affinity());
                assertTrue(placement.lane() >= 0);
                assertEquals(-1, placement.cpu());
            } finally { actor.stop(); }
        }
    }

    @Test
    void invalidOrUnsupportedCpuBindingFailsBeforeStartingLanes() {
        AtomicInteger created = new AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> new ActorAffinityDispatcher(
                2, 8, new int[] { -1 }, lane -> {
                    created.incrementAndGet(); return Executors.newSingleThreadExecutor();
                }));
        assertEquals(0, created.get());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    void linuxActorExecutesOnItsConfiguredCpu() throws Exception {
        int cpu = NativeCarrierExecutor.currentCpu();
        assertTrue(cpu >= 0);
        String previousMode = System.getProperty("ores.actor.affinity");
        String previousCpus = System.getProperty("ores.actor.cpus");
        try {
            System.setProperty("ores.actor.affinity", "cpu");
            System.setProperty("ores.actor.cpus", Integer.toString(cpu));
            try (var runtime = new ActorRuntime()) {
                AtomicInteger observed = new AtomicInteger(-1);
                var actor = runtime.spawnShared(() -> (message, context) -> {
                    observed.set(NativeCarrierExecutor.currentCpu());
                    context.self().stop();
                });
                var placement = runtime.placementFor(actor).orElseThrow();
                assertEquals("cpu", placement.affinity());
                assertEquals(cpu, placement.cpu());
                actor.send("run");
                actor.done().get(2, TimeUnit.SECONDS);
                assertEquals(cpu, observed.get());
            }
        } finally {
            if (previousMode == null) System.clearProperty("ores.actor.affinity");
            else System.setProperty("ores.actor.affinity", previousMode);
            if (previousCpus == null) System.clearProperty("ores.actor.cpus");
            else System.setProperty("ores.actor.cpus", previousCpus);
        }
    }

}

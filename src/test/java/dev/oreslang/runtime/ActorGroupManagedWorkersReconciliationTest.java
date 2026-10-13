package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Current-main integration regressions for structured worker ownership.
 * Intentional API distinction: lifecycle ownership != cooperative membership,
 * workersDone() is a snapshot and closedDone() is the persistent close fence.
 *
 * Adapted from the useful unique coverage in stale ActorGroup PR #574.
 */
@Timeout(15)
final class ActorGroupManagedWorkersReconciliationTest {

    @Test
    void immediateAndReadySpawnsHaveManagedRefsWithoutImplicitMembership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> immediate = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { });
            assertEquals(1, group.workerCount());
            assertTrue(group.workers().contains(immediate));
            assertFalse(group.contains(immediate),
                    "structured lifecycle ownership must not imply cooperative membership");
            immediate.ready().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> awaited = group.<String>spawnReadyWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }).get(5, TimeUnit.SECONDS);
            assertTrue(awaited.isAlive());
            assertTrue(awaited.ready().isDone());
            assertEquals(2, group.workerCount());
            assertEquals(0, group.memberCount());

            OresFuture<Void> closed = group.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS),
                    "cancelled workers settle the group with a typed failure");
            assertTrue(immediate.done().isDone());
            assertTrue(awaited.done().isDone());
            assertEquals(0, group.workerCount());
            assertThrows(IllegalStateException.class, () -> group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }));
        }
    }

    @Test
    void explicitManagedOwnershipDoesNotStopMembershipOnlyActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> owned = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            ActorRuntime.ActorRef<String> cooperative = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            assertSame(owned, group.manageWorker(owned));
            assertSame(owned, group.manageWorker(owned));
            assertFalse(group.contains(owned));
            group.join(cooperative, group.joinCapability());
            assertEquals(1, group.workerCount());
            assertEquals(1, group.memberCount());

            OresFuture<Void> closed = group.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
            assertTrue(owned.done().isDone());
            assertFalse(owned.isAlive());
            assertTrue(cooperative.isAlive(),
                    "membership alone cannot grant lifecycle cancellation authority");
            cooperative.stop();
            cooperative.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerHandoffTransfersExactlyOneManagerAndSurvivesOldGroupClose() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup first = runtime.createActorGroup();
            ActorRuntime.ActorGroup second = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = runtime.spawnShared(
                    () -> (message, turn) -> { });
            first.manageWorker(worker);
            assertThrows(IllegalStateException.class, () -> second.manageWorker(worker));
            assertTrue(first.releaseWorker(worker));
            assertFalse(first.releaseWorker(worker));
            assertSame(worker, second.manageWorker(worker));
            first.close();
            assertTrue(worker.isAlive(), "old manager must not stop an explicitly released worker");
            OresFuture<Void> closed = second.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
            assertTrue(worker.done().isDone());
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void closeBarrierWaitsForActiveWorkerTurnAndCannotBeCanceled() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroup other = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> worker = runtime.spawnShared(
                    () -> (value, ctx) -> {
                        entered.countDown();
                        if (!exit.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("active turn did not resume");
                        }
                    });
            group.manageWorker(worker);
            worker.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> closeFence;
            try {
                closeFence = group.closeAsync();
                assertSame(closeFence, group.closedDone());
                assertFalse(closeFence.isDone(),
                        "closing registration is not completion of an in-flight turn");
                assertFalse(closeFence.cancel(false),
                        "observers must not cancel manager-owned finalization");
                assertThrows(IllegalStateException.class, () -> other.manageWorker(worker));
            } finally {
                exit.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closeFence.get(5, TimeUnit.SECONDS),
                    "manager shutdown should report child cancellation");
            assertTrue(worker.done().isDone());
            assertFalse(worker.isAlive());
            other.close();
        }
    }

    @Test
    void rejectedInitializationReleasesWorkerAndCannotAdoptForeignRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreignRuntime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> foreign = foreignRuntime.spawnShared(
                    () -> (message, turn) -> { });
            assertThrows(IllegalArgumentException.class, () -> group.manageWorker(foreign));

            OresFuture<ActorRuntime.ActorRef<String>> failed = group.spawnReadyWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> { throw new IllegalStateException("worker-init-failed"); });
            java.util.concurrent.ExecutionException exception = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> failed.get(5, TimeUnit.SECONDS));
            assertEquals("worker-init-failed", exception.getCause().getMessage());

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (group.workerCount() != 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(0, group.workerCount(), "failed actor must not remain retained");
            assertSame(group.closedDone(), group.closeAsync());
            group.closedDone().get(5, TimeUnit.SECONDS);
            foreign.stop();
            foreign.done().get(5, TimeUnit.SECONDS);
        }
    }
}

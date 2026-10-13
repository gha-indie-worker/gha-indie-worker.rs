package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Managed workers are a lifecycle opt-in, not an implicit membership right. */
@Timeout(15)
final class ActorGroupManagedWorkerTest {

    @Test
    void spawnWorkerRetainsRefAndReadinessReturnsTheSameRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });

            assertEquals(1, group.workerCount());
            assertSame(worker, group.workers().getFirst());
            assertFalse(group.contains(worker), "ownership must not silently confer event-bus membership");
            assertFalse(worker.readyRef().cancel(false), "readiness observation cannot cancel the actor");
            assertSame(worker, worker.readyRef().get(5, TimeUnit.SECONDS));
            assertEquals(1, group.workersReady().get(5, TimeUnit.SECONDS).size());
            assertTrue(worker.isAlive());

            worker.stop();
            worker.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, group.workerCount(), "finalized refs must be evicted");
        }
    }

    @Test
    void groupCloseCancelsOnlyManagedWorkersNotOrdinaryMembers() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });
            ActorRuntime.ActorRef<Integer> memberOnly = runtime.spawnPrivate(
                    context -> (message, actorContext) -> { });
            group.join(memberOnly, group.joinCapability());

            worker.ready().get(5, TimeUnit.SECONDS);
            memberOnly.ready().get(5, TimeUnit.SECONDS);
            assertEquals(1, group.workerCount());
            assertEquals(1, group.memberCount());

            group.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(worker.isAlive());
            assertTrue(memberOnly.isAlive(), "ordinary cooperating member must not be cancelled");
            assertEquals(0, group.workerCount());

            memberOnly.stop();
            memberOnly.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerStartupFailureFailsReadinessAndEvictsManagedRef() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> { throw new IllegalStateException("start failed"); });
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> worker.readyRef().get(5, TimeUnit.SECONDS));
            assertEquals("start failed", failure.getCause().getMessage());
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, group.workerCount());
        }
    }

    @Test
    void structuredManagementIsExclusiveAcrossGroupsAndIdempotentWithinOneGroup()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup owner = runtime.createActorGroup();
            ActorRuntime.ActorGroup unrelated = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = owner.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });

            assertSame(worker, owner.manageWorker(worker),
                    "registering the same worker twice in its owning group is idempotent");
            assertEquals(1, owner.workerCount());
            assertThrows(IllegalStateException.class, () -> unrelated.manageWorker(worker),
                    "a second cooperation group must not acquire lifecycle ownership");
            assertEquals(0, unrelated.workerCount());

            worker.getReady().get(5, TimeUnit.SECONDS);
            unrelated.close();
            assertTrue(worker.isAlive(), "closing a non-owner cannot cancel the worker");

            owner.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void managementRejectsCrossRuntimeRefsAndRequiresActiveActor() throws Exception {
        try (ActorRuntime one = new ActorRuntime();
             ActorRuntime two = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = one.createActorGroup();
            ActorRuntime.ActorRef<String> foreign = two.spawnPrivate(
                    context -> (message, actorContext) -> { });
            assertThrows(IllegalArgumentException.class, () -> group.manageWorker(foreign));
            assertEquals(0, group.workerCount());
            foreign.stop();
            foreign.done().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<Integer> completed = one.spawnPrivate(
                    context -> (message, actorContext) -> { });
            completed.stop();
            completed.done().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> group.manageWorker(completed));
        }
    }
}

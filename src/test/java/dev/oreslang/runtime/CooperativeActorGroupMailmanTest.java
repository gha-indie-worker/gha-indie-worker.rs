package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CooperativeActorGroupMailmanTest {

    @Test
    void pendingMailmanWaitReleasesSingleControlCarrierAndPreservesSequence()
            throws Exception {
        ExecutorService control = Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("test-control-0")
                        .unstarted(runnable));
        ActorRuntime.ControlDispatcher dispatcher = controlDispatcher(control);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                dispatcher)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            OresFuture<String> gate = new OresFuture<>();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch suspended = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(2);
            AtomicReference<ActorGroupContext> captured = new AtomicReference<>();
            AtomicReference<String> firstCarrier = new AtomicReference<>();
            AtomicReference<String> resumedCarrier = new AtomicReference<>();
            List<Integer> order = Collections.synchronizedList(new ArrayList<>());

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                order.add(message);
                delivered.countDown();
            });

            group.installCooperativeMailman(8, (mail, context) -> {
                captured.compareAndSet(null, context);
                if (mail.message().equals(1)) {
                    return new OresScheduler.Task<>() {
                        private int pc;

                        @Override
                        public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                            if (pc == 0) {
                                assertTrue(resume.initial());
                                assertTrue(ActorRuntime.inActorGroupMailmanExecution());
                                firstCarrier.set(Thread.currentThread().getName());
                                suspended.countDown();
                                pc = 1;
                                return OresScheduler.await(gate);
                            }
                            if (pc == 1) {
                                assertFalse(resume.initial());
                                assertEquals("released", resume.value());
                                assertTrue(ActorRuntime.inActorGroupMailmanExecution());
                                resumedCarrier.set(Thread.currentThread().getName());
                                context.send(sink, 1);
                                pc = 2;
                                return OresScheduler.done(null);
                            }
                            throw new IllegalStateException("mailman task resumed after completion");
                        }
                    };
                }

                return CooperativeActorMailman.sync(() -> context.send(sink, 2));
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(1);
            emitter.send(2);

            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            assertThrows(SecurityException.class, () -> captured.get().memberCount(),
                    "Mailman context must be inactive while its task is suspended");

            dispatcher.execute(peerRan::countDown);
            assertTrue(peerRan.await(2, TimeUnit.SECONDS),
                    "peer CONTROL work must progress while Mailman waits");
            assertTrue(order.isEmpty(),
                    "later outbox entries must not pass the suspended mail item");

            Thread completion = Thread.ofPlatform()
                    .name("future-producer")
                    .start(() -> gate.completeFromRuntime("released"));
            completion.join();

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), order);
            assertEquals("test-control-0", firstCarrier.get());
            assertEquals("test-control-0", resumedCarrier.get(),
                    "one-carrier test may reuse the same physical thread only after suspension released it");
            assertThrows(SecurityException.class, () -> captured.get().groupId(),
                    "Mailman context must become inactive again after task completion");
        } finally {
            control.shutdownNow();
            assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeWithdrawsSuspendedWaiterAndLateCompletionCannotResurrectMail()
            throws Exception {
        ExecutorService control = Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("test-control-close-0")
                        .unstarted(runnable));
        ActorRuntime.ControlDispatcher dispatcher = controlDispatcher(control);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                dispatcher)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            OresFuture<Void> never = new OresFuture<>();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch suspended = new CountDownLatch(1);
            AtomicInteger resumes = new AtomicInteger();

            group.installCooperativeMailman(4, (mail, context) -> new OresScheduler.Task<>() {
                private int pc;

                @Override
                public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                    resumes.incrementAndGet();
                    if (pc == 0) {
                        pc = 1;
                        suspended.countDown();
                        return OresScheduler.await(never);
                    }
                    throw new AssertionError("closed Mailman was resurrected");
                }
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(List.of(message));
                    context.self().stop();
                }
            });

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(1);
            assertTrue(suspended.await(5, TimeUnit.SECONDS));
            // The latch fires inside resume(), before the scheduler installs
            // the waiter returned by that turn. Drain the single control
            // carrier so the assertion observes completed registration.
            control.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(1, never.pendingRuntimeWaiterCount());

            group.close();

            assertEquals(0, never.pendingRuntimeWaiterCount(),
                    "group close must detach the pending Future waiter");
            assertEquals(0, group.mailmanOutboxSize());
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));

            never.completeFromRuntime(null);
            Thread.sleep(100);
            assertEquals(1, resumes.get(),
                    "late Future completion must not re-enter a closed Mailman");
        } finally {
            control.shutdownNow();
            assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void terminalAwaitDefersResumeAdmissionUntilPriorControlTurnReleasesLease()
            throws Exception {
        ExecutorService control = Executors.newFixedThreadPool(
                2,
                runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("test-control-parallel")
                        .unstarted(runnable));
        AtomicInteger submissions = new AtomicInteger();
        ActorRuntime.ControlDispatcher dispatcher = task -> {
            submissions.incrementAndGet();
            assertFalse(
                    ActorRuntime.inActorGroupMailmanExecution(),
                    "a cooperative resume must not be submitted while the prior Mailman turn is active");
            OresFuture<Void> completion = new OresFuture<>();
            control.execute(() -> {
                try {
                    task.run();
                    completion.completeFromRuntime(null);
                } catch (Throwable failure) {
                    completion.failFromRuntime(failure);
                }
            });
            return completion;
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                dispatcher)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                delivered.countDown();
                context.self().stop();
            });

            group.installCooperativeMailman(4, (mail, context) -> new OresScheduler.Task<>() {
                private int pc;

                @Override
                public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                    if (pc == 0) {
                        pc = 1;
                        return OresScheduler.await(OresFuture.completed("already-ready"));
                    }
                    if (pc == 1) {
                        assertEquals("already-ready", resume.value());
                        context.send(sink, 1);
                        pc = 2;
                        return OresScheduler.done(null);
                    }
                    throw new IllegalStateException("terminal-await task resumed after completion");
                }
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                    context.self().stop();
                }
            });

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(1);

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(sink.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(
                    2,
                    submissions.get(),
                    "one initial CONTROL turn plus one post-lease resume should be admitted");
        } finally {
            control.shutdownNow();
            assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void taskFactoryFailureReleasesDequeuedPayloadReservation()
            throws Exception {
        ExecutorService control = Executors.newSingleThreadExecutor(
                runnable -> Thread.ofPlatform()
                        .daemon(true)
                        .name("test-control-factory-failure")
                        .unstarted(runnable));
        ActorRuntime.ControlDispatcher dispatcher = controlDispatcher(control);

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                dispatcher)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch factoryEntered = new CountDownLatch(1);
            CountDownLatch peerRan = new CountDownLatch(1);

            group.installCooperativeMailman(4, (mail, context) -> {
                factoryEntered.countDown();
                throw new IllegalArgumentException("factory boom");
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(List.of(message, message + 1, message + 2));
                    context.self().stop();
                }
            });

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(7);

            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));

            // FIFO on the same single CONTROL executor proves the failing
            // Mailman turn has fully unwound and runQuantum's failure cleanup ran.
            dispatcher.execute(peerRan::countDown);
            assertTrue(peerRan.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));

            assertFalse(group.hasMailman());
            assertEquals(0, group.mailmanOutboxSize());
            assertEquals(
                    0L,
                    runtime.sharedMemoryBytes(),
                    "factory failure must release the dequeued envelope reservation");
        } finally {
            control.shutdownNow();
            assertTrue(control.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static ActorRuntime.ControlDispatcher controlDispatcher(ExecutorService control) {
        return task -> {
            OresFuture<Void> completion = new OresFuture<>();
            control.execute(() -> {
                try {
                    task.run();
                    completion.completeFromRuntime(null);
                } catch (Throwable failure) {
                    completion.failFromRuntime(failure);
                }
            });
            return completion;
        };
    }
}

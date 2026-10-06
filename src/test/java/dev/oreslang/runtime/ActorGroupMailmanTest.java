package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ActorGroupMailmanTest {
    @Test
    void contextCannotEscapeItsCallbackOrBeReusedByTheNextCallback() throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(), vm::executeControl)) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            AtomicReference<ActorGroupContext> captured = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch first = new CountDownLatch(1);
            CountDownLatch second = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> { });
            group.installMailman(4, (mail, context) -> {
                try {
                    assertEquals(group.id(), context.groupId());
                    if (captured.compareAndSet(null, context)) {
                        first.countDown();
                    } else {
                        ActorGroupContext stale = captured.get();
                        expectSecurity(stale::groupId, "stale identity capability");
                        expectSecurity(stale::memberCount, "stale observation capability");
                        expectSecurity(() -> stale.send(sink, 1), "stale mailbox send capability");
                        context.send(sink, 2);
                    }
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally {
                    if (mail.message().equals(2)) second.countDown();
                }
            });
            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                join.join();
                group.emit(message);
            });
            emitter.send(1);
            assertTrue(first.await(5, TimeUnit.SECONDS));
            expectSecurity(captured.get()::groupId, "context escaped to host thread");
            expectSecurity(captured.get()::memberCount, "context escaped to host thread");
            expectSecurity(() -> captured.get().send(sink, 3), "context escaped to host thread");
            emitter.send(2);
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void concurrentEmittersUseOneSerializedControlMailmanWithOrderedSequence()
            throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 8, 256),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();

            int messages = 96;
            CountDownLatch joined = new CountDownLatch(2);
            CountDownLatch delivered = new CountDownLatch(messages);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maxActive = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            List<Long> sequences = Collections.synchronizedList(new ArrayList<>());
            Set<String> carrierNames = ConcurrentHashMap.newKeySet();

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                delivered.countDown();
            });

            group.installMailman(128, (mail, context) -> {
                int now = active.incrementAndGet();
                maxActive.accumulateAndGet(now, Math::max);
                try {
                    if (!ActorRuntime.inActorGroupMailmanExecution()) {
                        callbackFailure.compareAndSet(
                                null,
                                new AssertionError("Mailman execution marker is missing"));
                    }
                    if (OresScheduler.current() != vm.rootScheduler()) {
                        callbackFailure.compareAndSet(
                                null,
                                new AssertionError("Mailman did not execute on VM root scheduler"));
                    }
                    carrierNames.add(Thread.currentThread().getName());
                    sequences.add(mail.sequence());
                    context.send(sink, (Integer) mail.message());
                } catch (Throwable failure) {
                    callbackFailure.compareAndSet(null, failure);
                } finally {
                    active.decrementAndGet();
                }
            });

            ActorRuntime.ActorRef<Integer> left = runtime.spawnShared(() -> (message, context) -> {
                if (message < 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });
            ActorRuntime.ActorRef<Integer> right = runtime.spawnShared(() -> (message, context) -> {
                if (message < 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });

            left.send(-1);
            right.send(-1);
            assertTrue(joined.await(5, TimeUnit.SECONDS));

            for (int i = 0; i < messages; i++) {
                (i % 2 == 0 ? left : right).send(i);
            }

            assertTrue(delivered.await(10, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertEquals(1, maxActive.get(), "only one logical Mailman may execute at a time");
            assertEquals(messages, sequences.size());
            for (int i = 0; i < messages; i++) {
                assertEquals((long) i, sequences.get(i));
            }
            assertFalse(carrierNames.isEmpty());
            assertTrue(
                    carrierNames.stream().allMatch(name -> name.startsWith("ores-control-plane-")),
                    "Mailman must execute on CONTROL carriers, not actor dispatchers");
        }
    }

    @Test
    void controlMailmanDoesNotAcquireSupervisorOrGroupManagerAuthority()
            throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.events().defineTopic(
                    "mailman-authority",
                    ActorEventBus.DeliveryPolicy.LOSSY,
                    1);

            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            CountDownLatch replyDelivered = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<Integer>> emitterRef = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                replyDelivered.countDown();
            });

            group.installMailman(8, (mail, context) -> {
                try {
                    expectSecurity(
                            () -> runtime.controlHandle(emitterRef.get()),
                            "Mailman must not acquire supervisor ActorControlHandle authority");
                    expectSecurity(
                            runtime::createActorGroup,
                            "Mailman must not create ActorGroups as host/supervisor");
                    expectSecurity(
                            group::rotateJoinCapability,
                            "Mailman must not rotate ActorGroup authority");
                    expectSecurity(
                            () -> group.events().publishSystem("mailman-authority", 1),
                            "Mailman CONTROL carrier must not publish as system/supervisor");
                    expectSecurity(
                            () -> runtime.spawnShared(() -> (message, actor) -> { }),
                            "Mailman must not use host actor construction");
                    expectSecurity(
                            () -> emitterRef.get().stop(),
                            "Mailman must not stop actors through ambient ActorRef authority");
                    expectSecurity(
                            () -> emitterRef.get().cancel(),
                            "Mailman must not cancel actors through ambient ActorRef authority");
                    expectSecurity(
                            () -> {
                                try {
                                    emitterRef.get().awaitTermination(1, TimeUnit.MILLISECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            },
                            "Mailman must not synchronously await actor termination");
                    expectSecurity(
                            () -> runtime.syncCell(1),
                            "Mailman must not create shared runtime state outside ActorGroupContext");
                    expectSecurity(
                            () -> runtime.shareReadonly(List.of(1)),
                            "Mailman must not create readonly shared values outside ActorGroupContext");
                    expectSecurity(
                            () -> runtime.setActorExitHook(ignored -> { }),
                            "Mailman must not mutate host actor-exit hooks");
                    expectSecurity(
                            group::memberCount,
                            "Mailman must not use captured ActorGroup authority directly");
                    assertTrue(context.memberCount() >= 1);
                    context.send(sink, 1);
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                } finally {
                    checked.countDown();
                }
            });

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                if (message == 0) {
                    group.joinCurrent(join);
                    joined.countDown();
                } else {
                    group.emit(message);
                }
            });
            emitterRef.set(emitter);

            emitter.send(0);
            assertTrue(joined.await(5, TimeUnit.SECONDS));
            emitter.send(1);

            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertTrue(replyDelivered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }

    @Test
    void groupCloseFencesAnAlreadyRunningMailmanFromLateContextSend()
            throws Exception {
        try (OresVM vm = OresVM.create(Runnable::run);
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                     ActorRuntime.TurnExecutor.direct(),
                     vm::executeControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            CountDownLatch joined = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch callbackDone = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> sink = runtime.spawnShared(() -> (message, context) -> {
                delivered.countDown();
            });

            group.installMailman(8, (mail, context) -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        observed.set(new AssertionError("test did not release running Mailman"));
                        return;
                    }
                    try {
                        context.send(sink, 1);
                        observed.set(new AssertionError(
                                "closed ActorGroup allowed a running Mailman to send"));
                    } catch (IllegalStateException expected) {
                        observed.set(expected);
                    }
                } finally {
                    callbackDone.countDown();
                }
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
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            group.close();
            release.countDown();

            assertTrue(callbackDone.await(5, TimeUnit.SECONDS));
            assertNotNull(observed.get());
            assertTrue(observed.get() instanceof IllegalStateException);
            assertEquals(1L, delivered.getCount(), "closed Mailman must not send after group teardown");
        }
    }

    @Test
    void boundedOutboxFailsClosedAndGroupTeardownReleasesPayloadAccounting()
            throws Exception {
        AtomicReference<Runnable> admitted = new AtomicReference<>();
        AtomicReference<OresFuture<Void>> dispatch = new AtomicReference<>();

        ActorRuntime.ControlDispatcher pausedControl = task -> {
            if (!admitted.compareAndSet(null, task)) {
                throw new AssertionError("only one Mailman CONTROL task should be admitted");
            }
            OresFuture<Void> future = new OresFuture<>();
            dispatch.set(future);
            return future;
        };

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(),
                pausedControl)) {

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.installMailman(2, (mail, context) -> { });

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> expectedOverflow = new AtomicReference<>();

            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                group.joinCurrent(join);
                try {
                    group.emit(List.of(1, 2, 3));
                    group.emit(List.of(4, 5, 6));
                    group.emit(List.of(7, 8, 9));
                } catch (IllegalStateException overflow) {
                    expectedOverflow.set(overflow);
                } finally {
                    context.self().stop();
                    done.countDown();
                }
            });

            emitter.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(expectedOverflow.get());
            assertTrue(expectedOverflow.get().getMessage().contains("outbox capacity"));
            assertEquals(2, group.mailmanOutboxSize());
            assertTrue(runtime.sharedMemoryBytes() > 0L);

            group.close();

            assertEquals(0, group.mailmanOutboxSize());
            assertEquals(
                    0L,
                    runtime.sharedMemoryBytes(),
                    "teardown must release all frozen queued Mailman payload reservations");

            // The admitted CONTROL task may arrive after close. It must be inert
            // and must not resurrect drained mail.
            Runnable task = admitted.get();
            assertNotNull(task);
            task.run();
            OresFuture<Void> future = dispatch.get();
            assertNotNull(future);
            future.completeFromRuntime(null);
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void callbackFailureStopsMailmanAndDrainsChannelPayloads() throws Exception {
        AtomicReference<Runnable> task = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(), work -> {
                    task.set(work);
                    return new OresFuture<>();
                })) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.installMailman(3, (mail, context) -> {
                callbacks.incrementAndGet();
                throw new IllegalArgumentException("callback failed");
            });
            CountDownLatch emitted = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                join.join();
                for (int i = 0; i < 3; i++) group.emit(List.of(i));
                context.self().stop();
                emitted.countDown();
            });
            emitter.send(0);
            assertTrue(emitted.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(3, group.mailmanOutboxSize());
            assertTrue(runtime.sharedMemoryBytes() > 0L);
            task.get().run();
            assertEquals(1, callbacks.get());
            assertFalse(group.hasMailman());
            assertEquals(0, group.mailmanOutboxSize());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void dispatchRejectionReleasesAdmittedPayloadAndStopsMailman() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8, 64),
                ActorRuntime.TurnExecutor.direct(), work -> {
                    throw new IllegalStateException("CONTROL admission rejected");
                })) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroupJoinCapability join = group.joinCapability();
            group.installMailman(2, (mail, context) -> {
                throw new AssertionError("rejected dispatch must not invoke callback");
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch emitted = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> emitter = runtime.spawnShared(() -> (message, context) -> {
                join.join();
                try {
                    group.emit(List.of(1, 2, 3));
                } catch (IllegalStateException rejection) {
                    failure.set(rejection);
                } finally {
                    context.self().stop();
                    emitted.countDown();
                }
            });
            emitter.send(0);
            assertTrue(emitted.await(5, TimeUnit.SECONDS));
            assertTrue(emitter.awaitTermination(5, TimeUnit.SECONDS));
            assertNotNull(failure.get());
            assertEquals("CONTROL admission rejected", failure.get().getMessage());
            assertFalse(group.hasMailman());
            assertEquals(0, group.mailmanOutboxSize());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    private static void expectSecurity(Runnable operation, String message) {
        try {
            operation.run();
            throw new AssertionError(message);
        } catch (SecurityException expected) {
            // Expected fail-closed boundary.
        }
    }
}

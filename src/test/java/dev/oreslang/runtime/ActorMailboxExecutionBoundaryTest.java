package dev.oreslang.runtime;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Instrumented compiler bridge; probes are host test state, never guest capabilities. */
@Timeout(20)
final class ActorMailboxExecutionBoundaryTest {
    @Test
    void initializationMailContinuationsAndCleanupShareOneExecutionLeaseForEveryKind() throws Exception {
        for (var kind : ActorRuntime.ActorKind.values()) {
            var threads = java.util.concurrent.ConcurrentHashMap.<Thread>newKeySet();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger cleanups = new AtomicInteger();
            CountDownLatch registered = new CountDownLatch(1);
            CountDownLatch releaseReceive = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(24);
            List<OresFuture<Integer>> completions = new ArrayList<>();
            for (int i = 0; i < 16; i++) completions.add(new OresFuture<>());
            Runnable enter = () -> {
                threads.add(Thread.currentThread());
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            };
            Runnable leave = active::decrementAndGet;
            try (var store = new SharedCodeImageStore();
                 var runtime = new ActorRuntime(IsolatePolicy.developer(),
                         new ActorRuntime.DispatcherConfig(4, 4, 1, 128));
                 var producers = Executors.newFixedThreadPool(4)) {
                var image = store.publish("execution-boundary.ores", Parser.parse("pub routine main(): void { return; }"));
                var executor = new ActorRuntime.ActorCodeExecutor() {
                    @Override public SharedCodeImageStore.CodeImage codeImage() { return image; }
                    @Override public ActorRuntime.ActorOwnedGuestState initializeActor(
                            String type, ActorRuntime.ActorContext<Object> context) {
                        enter.run();
                        try { return context.self()::id; }
                        finally { leave.run(); }
                    }
                    @Override public void receiveActor(String type, ActorRuntime.ActorOwnedGuestState state,
                            ActorRuntime.ActorInboxMail<Object> mail, ActorRuntime.ActorContext<Object> context)
                            throws Exception {
                        enter.run();
                        try {
                            assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                            if (mail.value().equals("register")) {
                                var target = runtime.captureCurrentContinuationTarget();
                                for (var future : completions) {
                                    runtime.enqueueOnCompletion(future, target, (value, failure) -> {
                                        enter.run();
                                        try {
                                            assertNull(failure);
                                            assertEquals(context.self().id(), ActorRuntime.currentActorId().orElseThrow());
                                            assertFalse(Thread.currentThread().getName().startsWith("pool-"));
                                            callbacks.incrementAndGet();
                                            completed.countDown();
                                        } finally { leave.run(); }
                                    });
                                }
                                registered.countDown();
                                assertTrue(releaseReceive.await(2, TimeUnit.SECONDS));
                            } else if (mail.value().equals("end")) {
                                runtime.endCurrentActorWithCleanup(() -> {
                                    enter.run();
                                    try { cleanups.incrementAndGet(); }
                                    finally { leave.run(); }
                                });
                            } else {
                                completed.countDown();
                            }
                        } finally { leave.run(); }
                    }
                };
                var actor = runtime.spawnCodeActor(kind, executor, "Worker");
                try {
                    actor.ready().get(2, TimeUnit.SECONDS);
                    assertTrue(runtime.mailboxUsesChannelTransport(actor));
                    actor.send("register");
                    assertTrue(registered.await(2, TimeUnit.SECONDS));
                    var tasks = new ArrayList<java.util.concurrent.Future<?>>();
                    for (var future : completions) tasks.add(producers.submit(() -> future.completeFromRuntime(1)));
                    for (var task : tasks) task.get(2, TimeUnit.SECONDS);
                    for (int i = 0; i < 8; i++) actor.send("message");
                    assertEquals(0, callbacks.get(), "completion producers cannot invoke the actor");
                    releaseReceive.countDown();
                    assertTrue(completed.await(2, TimeUnit.SECONDS), kind.toString());
                    actor.send("end");
                    actor.done().get(2, TimeUnit.SECONDS);
                    assertEquals(16, callbacks.get());
                    assertEquals(1, threads.size(), kind + " must retain its scheduling lane");
                    assertEquals(1, cleanups.get());
                    assertEquals(1, maximum.get(), kind + " must have one executing guest thread");
                    assertEquals(0, active.get());
                } finally { releaseReceive.countDown(); actor.cancel(); }
            }
        }
    }
}

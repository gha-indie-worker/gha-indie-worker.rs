package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Regression coverage for the actor/VM failure effect boundary. */
@Timeout(15)
final class ActorFailureIsolationTest {
    @Test
    void ordinaryThrowFailsOnlyTheMessageAndDoesNotPoisonTheMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch nextMessage = new CountDownLatch(1);
            AtomicInteger successful = new AtomicInteger();
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                if (message.equals("bad")) throw new OresFailure.Throw("bad message");
                successful.incrementAndGet();
                nextMessage.countDown();
            });
            ref.send("bad");
            ref.send("good");
            assertTrue(nextMessage.await(3, TimeUnit.SECONDS));
            assertEquals(1, successful.get());
            assertTrue(ref.isAlive());
            assertTrue(ref.failure().isEmpty(), "a message throw is not actor termination");
            assertEquals(1L, ref.messageFailureCount());
            assertEquals("bad message", ref.lastMessageFailure().orElseThrow().message());
            ref.stop();
        }
    }

    @Test
    void panicTerminatesOnlyTheOwningActorAndNotItsDispatcher() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch otherRan = new CountDownLatch(1);
            var survivor = runtime.<String>spawnShared(() -> (message, context) -> otherRan.countDown());
            var dying = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new OresFailure.Panic("invariant failed");
            });
            dying.send("panic");
            assertThrows(ExecutionException.class, () -> dying.done().get(3, TimeUnit.SECONDS));
            assertFalse(dying.isAlive());
            assertInstanceOf(OresFailure.Panic.class, dying.failure().orElseThrow());
            survivor.send("still running");
            assertTrue(otherRan.await(3, TimeUnit.SECONDS));
            assertTrue(survivor.isAlive());
            survivor.stop();
        }
    }

    @Test
    void raisedEffectIsBoundToItsCreatingActorDomain() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            java.util.concurrent.atomic.AtomicReference<OresFailure.Raise> observed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch captured = new CountDownLatch(1);
            var ref = runtime.<String>spawnShared(() -> (message, context) -> {
                OresFailure.Raise raised = new OresFailure.Raise("origin");
                assertTrue(raised.localToCurrentActorDomain());
                observed.set(raised);
                captured.countDown();
            });
            ref.send("capture");
            assertTrue(captured.await(3, TimeUnit.SECONDS));
            assertFalse(observed.get().localToCurrentActorDomain());
            ref.stop();
        }
    }

    @Test
    void unrecoveredRaiseIsTerminalButAnotherActorKeepsRunning() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch otherRan = new CountDownLatch(1);
            var survivor = runtime.<String>spawnShared(() -> (message, context) -> otherRan.countDown());
            var dying = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new OresFailure.Raise("nonlocal escape");
            });
            dying.send("raise");
            assertThrows(ExecutionException.class, () -> dying.done().get(3, TimeUnit.SECONDS));
            assertInstanceOf(OresFailure.Raise.class, dying.failure().orElseThrow());
            survivor.send("ok");
            assertTrue(otherRan.await(3, TimeUnit.SECONDS));
            survivor.stop();
        }
    }
}

package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression for #403. A malicious/buggy TurnExecutor attempts a second
 * actor entry while the first carrier owns the actor's single-writer lease.
 * Even if the invariant fails closed, the rejected carrier must not retain
 * the actor's ThreadLocal authority when it is recycled.
 */
@Timeout(20)
final class ActorCarrierIdentityCleanupTest {
    @Test
    void leaseViolationDoesNotContaminateRejectedCarrier() throws Exception {
        CountDownLatch insideOwnerTurn = new CountDownLatch(1);
        CountDownLatch letOwnerLeave = new CountDownLatch(1);
        AtomicBoolean leakedToRejectedCarrier = new AtomicBoolean(true);
        AtomicReference<Throwable> ownerFailure = new AtomicReference<>();

        ActorRuntime.TurnExecutor adversarialExecutor = turn -> {
            Thread owner = new Thread(() -> {
                try {
                    turn.run();
                } catch (Throwable failure) {
                    ownerFailure.set(failure);
                }
            }, "ores-test-injected-owner");
            owner.setDaemon(true);
            owner.start();
            try {
                assertTrue(insideOwnerTurn.await(5, TimeUnit.SECONDS),
                        "initial actor turn did not enter");
                try {
                    // A second concurrent attempt must fail its beginTurn lease.
                    turn.run();
                } catch (IllegalStateException expected) {
                    // Both propagating and actor-supervised fail-stop are valid.
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally {
                leakedToRejectedCarrier.set(
                        ActorRuntime.inActorExecution()
                                || ActorRuntime.currentActorId().isPresent()
                                || ActorRuntime.currentActorKind() != null
                                || ActorRuntime.currentActorPolicy() != null
                                || ActorRuntime.currentActorExecutionDomain() != null);
                letOwnerLeave.countDown();
                try {
                    owner.join(5000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                assertFalse(owner.isAlive(), "owner turn must finish before test teardown");
            }
        };

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), config, adversarialExecutor)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                insideOwnerTurn.countDown();
                try {
                    assertTrue(letOwnerLeave.await(5, TimeUnit.SECONDS),
                            "rejected turn did not release owner");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                context.self().stop();
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(leakedToRejectedCarrier.get(),
                    "failed actor beginTurn left guest identity on a reusable carrier");
            assertNull(ownerFailure.get(), "original actor owner turn must still unwind safely");
            Throwable cause = ref.failure().orElseThrow(
                    () -> new AssertionError("overlapping turn must fail-stop the actor"));
            assertTrue(cause.getMessage().contains("single-writer execution lease"),
                    () -> "unexpected termination cause: " + cause);
        }
    }

    @Test
    void leaseReleaseFailureStillClearsCarrierIdentity() throws Exception {
        AtomicBoolean leakedAfterReleaseFailure = new AtomicBoolean(true);
        ActorRuntime.TurnExecutor executor = turn -> {
            try {
                turn.run();
            } finally {
                leakedAfterReleaseFailure.set(
                        ActorRuntime.inActorExecution()
                                || ActorRuntime.currentActorId().isPresent()
                                || ActorRuntime.currentActorKind() != null
                                || ActorRuntime.currentActorPolicy() != null
                                || ActorRuntime.currentActorExecutionDomain() != null);
            }
        };

        var config = new ActorRuntime.DispatcherConfig(1, 1, 8, 16);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor)) {
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                // Inject the otherwise-impossible accounting corruption through
                // host-side reflection. Production code has no API for this.
                // This specifically tests endTurn() throwing in finally.
                try {
                    Field actorsField = ActorRuntime.class.getDeclaredField("actors");
                    actorsField.setAccessible(true);
                    Object actor = ((Map<?, ?>) actorsField.get(context.runtime()))
                            .get(context.self().id());
                    assertNotNull(actor);
                    Field activeTurns = actor.getClass().getDeclaredField("activeTurns");
                    activeTurns.setAccessible(true);
                    assertEquals(1, activeTurns.getInt(actor));
                    activeTurns.setInt(actor, 0);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError("test failed to inject lease violation", failure);
                }
            });
            ref.send(1);
            assertTrue(ref.awaitTermination(10, TimeUnit.SECONDS));
            assertFalse(leakedAfterReleaseFailure.get(),
                    "failed endTurn must not strand the actor's ThreadLocal identity");
            Throwable cause = ref.failure().orElseThrow(
                    () -> new AssertionError("endTurn accounting failure must fail-stop actor"));
            assertTrue(cause.getMessage().contains("execution lease accounting violation"),
                    () -> "unexpected termination cause: " + cause);
        }
    }
}

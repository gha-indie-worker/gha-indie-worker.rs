package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorSelectFairnessIsolationTest {

    @Test
    void sameSharedCodeSiteHasIndependentFairnessCursorPerActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Object immutableCodeSite = new Object();
            CountDownLatch delivered = new CountDownLatch(4);
            Map<ActorRuntime.ActorId, CopyOnWriteArrayList<Long>> tickets =
                    new ConcurrentHashMap<>();

            ActorRuntime.Behavior<String> behavior = (message, context) -> {
                long ticket = runtime.nextActorLocalSelectTicket(immutableCodeSite);
                tickets.computeIfAbsent(
                                context.self().id(),
                                ignored -> new CopyOnWriteArrayList<>())
                        .add(ticket);
                delivered.countDown();
            };

            ActorRuntime.ActorRef<String> shared =
                    runtime.spawnShared(() -> behavior);
            ActorRuntime.ActorRef<String> isolated =
                    runtime.spawnPrivate(() -> behavior);

            shared.send("a");
            isolated.send("a");
            shared.send("b");
            isolated.send("b");

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(0L, 1L), tickets.get(shared.id()));
            assertEquals(List.of(0L, 1L), tickets.get(isolated.id()));

            shared.stop();
            isolated.stop();
            shared.done().get(5, TimeUnit.SECONDS);
            isolated.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void hostCannotMutateActorLocalFairnessState() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.nextActorLocalSelectTicket(new Object()));
            assertTrue(failure.getMessage().contains("actor turn"),
                    failure.getMessage());
        }
    }
}

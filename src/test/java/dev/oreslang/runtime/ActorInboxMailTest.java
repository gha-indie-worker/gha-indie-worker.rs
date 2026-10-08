package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorInboxMailTest {

    @Test
    void hostMessagesHaveNoSenderAndUsePerActorAdmissionSequence() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(2);
            List<ActorRuntime.ActorInboxMail<String>> observed =
                    new CopyOnWriteArrayList<>();

            var ref = runtime.<String>spawnPrivate(() -> (message, context) -> {
                var mail = context.currentMail().orElseThrow();
                observed.add(mail);
                delivered.countDown();
            });

            ref.send("first");
            ref.send("second");

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            assertEquals(2, observed.size());

            assertEquals(ref.id(), observed.get(0).recipient());
            assertEquals(0L, observed.get(0).sequence());
            assertEquals("first", observed.get(0).value());
            assertTrue(observed.get(0).sender().isEmpty());

            assertEquals(ref.id(), observed.get(1).recipient());
            assertEquals(1L, observed.get(1).sequence());
            assertEquals("second", observed.get(1).value());
            assertTrue(observed.get(1).sender().isEmpty());

            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void actorToActorMessageCarriesSenderIdentity() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch delivered = new CountDownLatch(1);
            AtomicReference<ActorRuntime.ActorInboxMail<String>> observed =
                    new AtomicReference<>();

            var target = runtime.<String>spawnShared(() -> (message, context) -> {
                observed.set(context.currentMail().orElseThrow());
                delivered.countDown();
                context.self().stop();
            });

            var source = runtime.<String>spawnShared(() -> (message, context) -> {
                target.send("from-actor");
                context.self().stop();
            });

            source.send("go");

            assertTrue(delivered.await(5, TimeUnit.SECONDS));
            var mail = observed.get();
            assertNotNull(mail);
            assertEquals(target.id(), mail.recipient());
            assertEquals(source.id(), mail.sender().orElseThrow());
            assertEquals(0L, mail.sequence());
            assertEquals("from-actor", mail.value());

            source.done().get(5, TimeUnit.SECONDS);
            target.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void startupHasNoCurrentMailAndEnvelopeDoesNotLeakPastHandler() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            AtomicReference<ActorRuntime.ActorContext<String>> saved =
                    new AtomicReference<>();
            CountDownLatch handled = new CountDownLatch(1);

            var ref = runtime.<String>spawnPrivateTrusted(factoryContext -> {
                assertTrue(factoryContext.currentMail().isEmpty());
                saved.set(factoryContext);
                return (message, context) -> {
                    assertTrue(context.currentMail().isPresent());
                    handled.countDown();
                };
            });

            ref.ready().get(5, TimeUnit.SECONDS);
            assertTrue(saved.get().currentMail().isEmpty());

            ref.send("one");
            assertTrue(handled.await(5, TimeUnit.SECONDS));

            // Finalization is the externally observable barrier after the
            // handler has unwound and activeMail has been cleared.
            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
            assertTrue(saved.get().currentMail().isEmpty());
        }
    }
}

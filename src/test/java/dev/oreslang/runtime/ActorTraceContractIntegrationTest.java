package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Captures actual mailbox admissions and on-actor handler deliveries.
 *
 * Output is a causally reconstructed trace after quiescence, not timestamped
 * scheduler telemetry. The host is not misrepresented as an Oreslang actor.
 */
@Timeout(30)
final class ActorTraceContractIntegrationTest {
    private static final int MESSAGES = 24;

    @Test
    void realSharedActorHostIngressHasValidCanonicalTrace() throws Exception {
        List<String> admitted = new ArrayList<>();
        List<ActorRuntime.ActorInboxMail<String>> observed = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(MESSAGES);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                observed.add(context.currentMail().orElseThrow());
                delivered.countDown();
            });
            for (int i = 1; i <= MESSAGES; i++) {
                String id = "m" + i;
                actor.send(id); // only record the admission after send succeeded
                admitted.add(id);
            }
            assertTrue(delivered.await(12, TimeUnit.SECONDS),
                    "native JVM actor handlers did not receive all messages");
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), "shared actor execution failed");
        }

        assertEquals(MESSAGES, observed.size());
        for (int i = 0; i < MESSAGES; i++) {
            var envelope = observed.get(i);
            assertTrue(envelope.sender().isEmpty(),
                    "an external sender must not have an actor identity");
            assertEquals(i, envelope.sequence(), "mailbox sequence must be ordered");
            assertEquals(admitted.get(i), envelope.value(),
                    "handler delivery must equal admitted message");
        }

        // Source event values and message ordering derive from successful
        // admission records and observed handler mailbox envelopes.
        StringBuilder json = new StringBuilder(5000);
        json.append("{\"schema_version\":\"1.0.0\",\"complete\":true,\"events\":[");
        int seq = 0;
        json.append("{\"seq\":").append(seq++)
                .append(",\"kind\":\"spawn\",\"actor\":\"graal_actor\"}");
        for (String id : admitted) {
            json.append(",{\"seq\":").append(seq++)
                    .append(",\"kind\":\"admit\",\"actor\":\"graal_actor\",\"message_id\":\"")
                    .append(id).append("\"}");
        }
        for (var envelope : observed) {
            json.append(",{\"seq\":").append(seq++)
                    .append(",\"kind\":\"receive\",\"actor\":\"graal_actor\",\"message_id\":\"")
                    .append(envelope.value()).append("\"}");
        }
        json.append(",{\"seq\":").append(seq++)
                .append(",\"kind\":\"terminate\",\"actor\":\"graal_actor\"}]}");

        Path destination = Path.of("target", "actor-trace-graal.json");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, json.toString() + "\n", StandardCharsets.UTF_8);
    }
}

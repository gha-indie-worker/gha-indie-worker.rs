package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static dev.oreslang.runtime.MessagePackCollectionCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class MessagePackActorMailboxIntegrationTest {
    @Test
    void sharedActorReceivesOwnedBinarySnapshotBeforeQueueAdmission() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Shared.Strings.v1", list(string())));
            ArrayList<String> senderOwned = new ArrayList<>(List.of("first"));
            ref.send(senderOwned);
            senderOwned.set(0, "changed");
            Object received = delivered.poll(5, TimeUnit.SECONDS);
            assertEquals(List.of("first"), received);
            assertNotSame(senderOwned, received);
            assertThrows(UnsupportedOperationException.class,
                    () -> ((List<Object>) received).add("mutated"));
        }
    }

    @Test
    void privateActorReceivesNestedTupleAndList() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnPrivateTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Private.Nested.v1",
                            tuple(bool(), list(string()))));
            ref.send(List.of(true, new ArrayList<>(List.of("a", "b"))));
            Object received = delivered.poll(5, TimeUnit.SECONDS);
            assertEquals(List.of(true, List.of("a", "b")), received);
        }
    }

    @Test
    void invalidMessageFailsBeforeAdmissionAndDoesNotPoisonMailbox() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            runtime.bindSerializableMailbox(ref,
                    new MailboxSchema("test.Strict.Strings.v1", list(string())));
            assertThrows(IllegalArgumentException.class,
                    () -> ref.send(List.of("ok", new Object())));
            ref.send(List.of("after-error"));
            assertEquals(List.of("after-error"), delivered.poll(5, TimeUnit.SECONDS));
            assertNull(delivered.poll(100, TimeUnit.MILLISECONDS));
            assertThrows(IllegalStateException.class,
                    () -> runtime.bindSerializableMailbox(ref,
                            new MailboxSchema("test.Changed.v1", list(string()))));
        }
    }

    @Test
    void lateSchemaBindingCannotRetroactivelyChangeMailboxMeaning() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var delivered = new ArrayBlockingQueue<Object>(2);
            var ref = runtime.<Object>spawnSharedTrusted(
                    context -> (value, turn) -> delivered.add(value));
            ref.send(List.of("already-sent"));
            assertEquals(List.of("already-sent"), delivered.poll(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> runtime.bindSerializableMailbox(ref,
                            new MailboxSchema("test.TooLate.v1", list(string()))));
        }
    }
}

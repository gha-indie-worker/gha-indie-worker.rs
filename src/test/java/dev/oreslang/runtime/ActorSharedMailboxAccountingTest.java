package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial accounting probe: reflection is test-only, never a guest API.
 * Proves that an over-release cannot silently debit the shared runtime budget.
 */
@Timeout(15)
final class ActorSharedMailboxAccountingTest {

    @Test
    void oversizedAndDuplicateReleaseLeaveBothAccountingLayersUnchanged() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, context) -> { });
            actor.ready().get(5, TimeUnit.SECONDS);

            Field actorsField = ActorRuntime.class.getDeclaredField("actors");
            actorsField.setAccessible(true);
            Object cell = ((Map<?, ?>) actorsField.get(runtime)).get(actor.id());
            assertNotNull(cell, "actor cell should remain alive during accounting probe");

            Field queuedBytesField = cell.getClass().getDeclaredField("sharedMailboxBytes");
            queuedBytesField.setAccessible(true);
            AtomicLong queuedBytes = (AtomicLong) queuedBytesField.get(cell);

            Method reserve = cell.getClass().getDeclaredMethod("reserveSharedMailbox", long.class);
            Method release = cell.getClass().getDeclaredMethod("releaseSharedMailbox", long.class);
            reserve.setAccessible(true);
            release.setAccessible(true);

            long baseline = runtime.sharedMemoryBytes();
            assertEquals(0L, queuedBytes.get());
            reserve.invoke(cell, 64L);
            assertEquals(64L, queuedBytes.get());
            assertEquals(baseline + 64L, runtime.sharedMemoryBytes());

            InvocationTargetException oversized = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(cell, 65L));
            assertInstanceOf(IllegalStateException.class, oversized.getCause());
            assertTrue(oversized.getCause().getMessage().contains(
                    "shared actor mailbox memory accounting underflow"));
            assertEquals(64L, queuedBytes.get());
            assertEquals(baseline + 64L, runtime.sharedMemoryBytes());

            release.invoke(cell, 64L);
            assertEquals(0L, queuedBytes.get());
            assertEquals(baseline, runtime.sharedMemoryBytes());

            InvocationTargetException duplicate = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(cell, 64L));
            assertInstanceOf(IllegalStateException.class, duplicate.getCause());
            assertEquals(0L, queuedBytes.get());
            assertEquals(baseline, runtime.sharedMemoryBytes());

            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> actor.failure().toString());
        }
    }
}

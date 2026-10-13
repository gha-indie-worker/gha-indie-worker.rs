package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only reflection probes for the supervisor's shared aggregate budget. */
@Timeout(20)
final class ActorSharedRuntimeAccountingTest {

    private static Method operation(String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = ActorRuntime.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    @Test
    void oversizedDuplicateAndNegativeReleasesNeverAlterTheBudget() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = operation("reserveSharedRuntimeBytes", long.class, String.class);
            Method release = operation("releaseSharedRuntimeBytes", long.class);
            assertEquals(0L, runtime.sharedMemoryBytes());
            reserve.invoke(runtime, 64L, "shared budget regression");
            assertEquals(64L, runtime.sharedMemoryBytes());

            InvocationTargetException oversized = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, 65L));
            assertInstanceOf(IllegalStateException.class, oversized.getCause());
            assertTrue(oversized.getCause().getMessage().contains(
                    "shared actor memory accounting underflow"));
            assertEquals(64L, runtime.sharedMemoryBytes(),
                    "an over-release must not discard a valid outstanding reservation");

            InvocationTargetException negative = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, -1L));
            assertInstanceOf(IllegalArgumentException.class, negative.getCause());
            assertEquals(64L, runtime.sharedMemoryBytes());

            release.invoke(runtime, 64L);
            assertEquals(0L, runtime.sharedMemoryBytes());
            InvocationTargetException duplicate = assertThrows(
                    InvocationTargetException.class, () -> release.invoke(runtime, 64L));
            assertInstanceOf(IllegalStateException.class, duplicate.getCause());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void concurrentDuplicateReleasesHaveExactlyOneWinner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = operation("reserveSharedRuntimeBytes", long.class, String.class);
            Method release = operation("releaseSharedRuntimeBytes", long.class);
            reserve.invoke(runtime, 64L, "concurrent release regression");

            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            Runnable contender = () -> {
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    release.invoke(runtime, 64L);
                    accepted.incrementAndGet();
                } catch (InvocationTargetException ex) {
                    if (ex.getCause() instanceof IllegalStateException) {
                        rejected.incrementAndGet();
                    } else {
                        throw new AssertionError("unexpected aggregate release failure", ex.getCause());
                    }
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
            };

            try (var executor = Executors.newFixedThreadPool(2)) {
                var a = executor.submit(contender);
                var b = executor.submit(contender);
                start.countDown();
                a.get(5, TimeUnit.SECONDS);
                b.get(5, TimeUnit.SECONDS);
            }

            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(0L, runtime.sharedMemoryBytes());
        }
    }
}

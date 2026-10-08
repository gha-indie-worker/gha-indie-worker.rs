package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ActorDispatcherConfigTest {
    @Test
    void explicitSharedCarrierLimitIsHonoredAndValidated() {
        String key = "ores.runtime.actor.shared.parallelism";
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, "1");
            assertEquals(1, ActorRuntime.DispatcherConfig.defaults().sharedParallelism());
            System.setProperty(key, "0");
            assertThrows(IllegalArgumentException.class, ActorRuntime.DispatcherConfig::defaults);
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}

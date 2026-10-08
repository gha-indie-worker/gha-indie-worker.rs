package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeBuildModeTest {
    @Test void nativeCapabilitiesCannotBeEnabledByExecutionPolicy() {
        for (var mode : ExecutionProfile.Mode.values()) {
            assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.JVM, mode));
            if (mode == ExecutionProfile.Mode.AOT) {
                assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.AOT, mode));
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.AOT, mode));
            }
            if (mode == ExecutionProfile.Mode.JIT) {
                assertThrows(IllegalArgumentException.class,
                        () -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.HYBRID, mode));
            } else {
                assertDoesNotThrow(() -> NativeBuildMode.requireCompatible(NativeBuildMode.Kind.HYBRID, mode));
            }
        }
    }
    @Test void jvmDoesNotPretendToBeANativeImage() {
        assertEquals(NativeBuildMode.Kind.JVM, NativeBuildMode.kind());
        assertEquals("jit", NativeBuildMode.defaultExecutionMode());
        assertEquals(ExecutionProfile.Mode.JIT,
                IsolatePolicy.executionProfileFromApplicationArguments(new String[0]).mode());
        assertEquals(ExecutionProfile.Mode.AOT,
                IsolatePolicy.executionProfileFromApplicationArguments(new String[] {"--ores-execution-mode=aot"}).mode());
    }
}

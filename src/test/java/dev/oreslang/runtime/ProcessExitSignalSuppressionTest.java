package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ProcessExitSignalSuppressionTest {
    @Test
    void exitRetainsOrderedCleanupFailuresWithoutCollectingStackTrace() {
        ProcessExitSignal exit = new ProcessExitSignal(23);
        IllegalStateException first = new IllegalStateException("first defer");
        IllegalArgumentException second = new IllegalArgumentException("second defer");

        exit.addSuppressed(first);
        exit.addSuppressed(second);

        assertEquals(23, exit.exitStatus());
        assertArrayEquals(new Throwable[] {first, second}, exit.getSuppressed());
        assertEquals(0, exit.getStackTrace().length,
                "process exit is a stackless runtime control signal");
    }

    @Test
    void invalidExitCodesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessExitSignal(-1));
        assertThrows(IllegalArgumentException.class, () -> new ProcessExitSignal(256));
    }
}

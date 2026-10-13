package dev.oreslang.launcher;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class MainExitStatusTest {
    @TempDir Path temp;

    private int exit(String source) throws Exception {
        Path path = temp.resolve("main.ores");
        Files.writeString(path, source);
        return LinkedProgramRunner.runWithExitStatus(path, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).exitStatus();
    }

    @Test void voidMainImpliesSuccess() throws Exception {
        assertEquals(0, exit("pub routine main(): void { return; }"));
    }

    @Test void integerMainReturnsZero() throws Exception {
        assertEquals(0, exit("pub routine main(): int { return 0; }"));
    }

    @Test void integerMainReturnsFailureCode() throws Exception {
        assertEquals(1, exit("pub routine main(): int { return 1; }"));
        assertEquals(42, exit("pub routine main(): int { return 42; }"));
        assertEquals(255, exit("pub routine main(): int { return 255; }"));
    }

    @Test void asynchronousIntegerMainYieldsExitStatus() throws Exception {
        assertEquals(8, exit("pub async routine main(): int { return 8; }"));
    }

    @Test void explicitProcessExitYieldsHostStatus() throws Exception {
        assertEquals(7, exit("pub routine main(): void { std.process.exit(7); }"));
    }

    @Test void explicitProcessExitDoesNotRequireIntegerMain() throws Exception {
        assertEquals(2, exit("pub routine main(): void { process.exit(2); }"));
    }

    @Test void explicitProcessExitInsideAsyncMainIsNotProcessGlobal() throws Exception {
        assertEquals(13, exit("pub async routine main(): void { std.process.exit(13); }"));
    }

    @Test void tooLargeIntegerExitFailsClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> exit("pub routine main(): int { return 256; }"));
    }

    @Test void outOfRangeExplicitProcessExitIsRejected() throws Exception {
        assertThrows(RuntimeException.class,
            () -> exit("pub routine main(): void { std.process.exit(256); }"));
    }

    @Test void unsupportedLinkedMainReturnValueIsRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,
            () -> exit("pub routine main(): String { return \"invalid\"; }"));
    }

    @Test void untrustedIsolateCannotForceProcessTermination() throws Exception {
        Path path = temp.resolve("untrusted.ores");
        Files.writeString(path, "pub routine main(): void { std.process.exit(3); }");
        assertThrows(RuntimeException.class, () ->
            LinkedProgramRunner.runWithExitStatus(path,
                    IsolatePolicy.developer().withoutCapabilities(
                            IsolatePolicy.Capability.PROCESS_EXIT),
                    ExecutionProfile.serverJit(), new ByteArrayOutputStream(),
                    new ByteArrayOutputStream()));
    }
}

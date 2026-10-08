package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FileRootPublicClassImportTest {
    @TempDir Path temp;

    @Test
    void publicFileRootClassAndConstructorAreRelativeFileExports() throws Exception {
        Path lib = temp.resolve("box.ores");
        Path main = temp.resolve("main.ores");

        Files.writeString(lib, """
                pub define class Box as
                  val int value;

                  pub constructor(int value) {
                    self.value = value;
                  }

                  pub get(): int {
                    return self.value;
                  }
                end
                """);

        Files.writeString(main, """
                import class Box from "./box.ores";

                pub fnc main(): void {
                  val Box box = new Box(7);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                main,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                out,
                new ByteArrayOutputStream());

        assertEquals("7", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void privateFileRootClassIsNotImportable() throws Exception {
        Path lib = temp.resolve("hidden.ores");
        Path main = temp.resolve("main-hidden.ores");

        Files.writeString(lib, """
                define class Hidden as
                end
                """);

        Files.writeString(main, """
                import class Hidden from "./hidden.ores";

                pub fnc main(): void {
                  return;
                }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));

        assertTrue(failure.getMessage().contains("does not match an exported declaration"));
    }
}

package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelfImportIsolationTest {
    @TempDir Path temp;

    @Test
    void explicitDotSelfImportIsRejectedBeforeResolution() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import * as file from ".";

                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }

    @Test
    void relativePathToTheSameFileIsRejected() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import * as file from "./main.ores";

                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }

    @Test
    void normalizedRelativeAliasToTheSameFileIsRejected() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import * as file from "./nested/../main.ores";

                pub routine main(): void { return; }
                """);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }

    @Test
    void filesystemAliasToTheSamePhysicalFileIsRejected() throws Exception {
        Path main = temp.resolve("main.ores");
        Files.writeString(main, """
                import * as file from "./alias.ores";

                pub routine main(): void { return; }
                """);

        Path alias = temp.resolve("alias.ores");
        try {
            Files.createLink(alias, main);
        } catch (UnsupportedOperationException | IOException failure) {
            Assumptions.assumeTrue(false, "hard links unavailable: " + failure);
        }

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(main));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }

    @Test
    void compilerSourceMapRejectsLexicallyResolvedSelfEdge() {
        IncrementalCompiler compiler = new IncrementalCompiler();

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(Map.of(
                        "pkg/main.ores",
                        """
                        import * as file from "./main.ores";

                        pub routine main(): void { return; }
                        """)));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }

    @Test
    void explicitResolverMappingCannotMapAnImportBackToItsImporter() {
        IncrementalCompiler compiler = new IncrementalCompiler();
        String unit = "pkg/main.ores";

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(
                        Map.of(unit, """
                                import * as file from "package-self";

                                pub routine main(): void { return; }
                                """),
                        Map.of(unit, Map.of("package-self", unit))));

        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }
}

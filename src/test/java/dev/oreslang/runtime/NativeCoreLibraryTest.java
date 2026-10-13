package dev.oreslang.runtime;

import dev.oreslang.interop.MixedSourceUnit;
import dev.oreslang.stdlib.CoreLibrarySources;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

final class NativeCoreLibraryTest {

    @Test
    void packagedStdModuleIsOreslangSourceAndRunsWithoutJavaInterop() throws Exception {
        String source = CoreLibrarySources.source("std/core");
        MixedSourceUnit unit = MixedSourceUnit.parse(
                CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.fileName("std/core"),
                source);

        assertTrue(unit.hasOresSource());
        assertFalse(unit.hasJavaSource(),
                "packaged std modules must not contain java/do-java islands");
        assertFalse(source.contains("java:"),
                "packaged std modules must not import Java host classes");

        Path entry = Files.createTempFile("ores-native-core-", ".ores");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Files.writeString(entry, """
                    import module core from "std/core";

                    pub routine main(): void {
                      stdio.println(core.clamp_int(15, 0, 10));
                      return;
                    }
                    """);

            var build = LinkedProgramRunner.run(
                    entry,
                    IsolatePolicy.developer(),
                    ExecutionProfile.serverJit(),
                    out,
                    err);

            assertTrue(build.units().containsKey("@std/core.ores"),
                    "std/core must be linked as an Oreslang source unit");
            assertTrue(
                    out.toString(StandardCharsets.UTF_8).contains("10"),
                    () -> "expected Oreslang stdlib output, stderr="
                            + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }


    @Test
    void everyPackagedStdModuleIsPureOreslangAndHasOnlyStdDependencies() throws Exception {
        Path root = Path.of("stdlib");
        assertTrue(Files.isDirectory(root), "packaged stdlib source root must exist");

        try (var stream = Files.walk(root)) {
            List<Path> modules = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".ores"))
                    .sorted()
                    .toList();
            assertFalse(modules.isEmpty(), "native-first stdlib must contain Oreslang modules");
            Set<String> actual = modules.stream()
                    .map(path -> root.relativize(path).toString().replace((char) 92, '/'))
                    .collect(Collectors.toSet());
            assertEquals(actual, CoreLibrarySources.packagedSourcePaths(),
                    "every packaged .ores file must be explicitly registered and vice versa");

            for (Path module : modules) {
                String source = Files.readString(module);
                String relative = root.relativize(module).toString().replace('\\', '/');
                String importPath = "std/" + relative.substring(0, relative.length() - ".ores".length());
                MixedSourceUnit unit = MixedSourceUnit.parse(
                        CoreLibrarySources.unitId(importPath),
                        module.getFileName().toString(),
                        source);

                assertFalse(unit.hasJavaSource(),
                        () -> relative + " contains Java source islands");
                assertFalse(source.contains("java:"),
                        () -> relative + " contains a java: host import");

                var program = dev.oreslang.parser.Parser.parse(unit.oresSource());
                for (var imported : program.imports()) {
                    assertTrue(
                            CoreLibrarySources.isCoreImport(imported.path()),
                            () -> relative + " depends on non-std module " + imported.path());
                }
            }
        }
    }


    @Test
    void bundledCoreCannotBeOverriddenByApplicationSourceRoot() throws Exception {
        Path project = Files.createTempDirectory("ores-stdlib-shadow-");
        Path std = Files.createDirectories(project.resolve("std"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(std.resolve("core.ores"), """
                    define module core
                      pub fnc min_int(int left, int right): int {
                        return 999;
                      }
                    end
                    """);
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                        stdio.println(core.min_int(9, 4));
                        return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            var build = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    out, new ByteArrayOutputStream());
            assertTrue(build.units().containsKey("@std/core.ores"));
            assertEquals("4", out.toString(StandardCharsets.UTF_8).trim(),
                    "application filesystem must never shadow std/core");
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(std.resolve("core.ores"));
            Files.deleteIfExists(std);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void unknownCoreImportNeverFallsBackToApplicationFilesystem() throws Exception {
        Path project = Files.createTempDirectory("ores-stdlib-unknown-");
        Path std = Files.createDirectories(project.resolve("std"));
        Path entry = project.resolve("main.ores");
        try {
            Files.writeString(std.resolve("phantom.ores"), """
                    define module phantom
                    end
                    """);
            Files.writeString(entry, """
                    import module phantom from "std/phantom";
                    pub routine main(): void { return; }
                    """);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry));
            assertTrue(failure.getMessage().contains("unknown Oreslang core-library module"));
        } finally {
            Files.deleteIfExists(entry);
            Files.deleteIfExists(std.resolve("phantom.ores"));
            Files.deleteIfExists(std);
            Files.deleteIfExists(project);
        }
    }

    @Test
    void strictIsolateMayValidateTrustedStdlibWithoutJavaCapability() throws Exception {
        Path entry = Files.createTempFile("ores-stdlib-strict-", ".ores");
        try {
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                        stdio.println(core.max_int(9, 4));
                        return;
                    }
                    """);
            var build = LinkedProgramRunner.validate(
                    entry, Map.of(), IsolatePolicy.strictFaas(), PermissionCheckMode.COMPILE);
            assertTrue(build.units().containsKey("@std/core.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void validationUsesExactPackagedStdlibWithoutHostInterop() throws Exception {
        Path entry = Files.createTempFile("ores-no-java-", ".ores");
        try {
            Files.writeString(entry, """
                    import module core from "std/core";
                    pub routine main(): void {
                      stdio.println(core.min_int(9, 4));
                      return;
                    }
                    """);
            var build = LinkedProgramRunner.validate(entry);
            assertTrue(build.units().containsKey("@std/core.ores"));
        } finally {
            Files.deleteIfExists(entry);
        }
    }

    @Test
    void coreImportPathsFailClosedOnTraversalAndUnknownModules() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/../secret"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std//core"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.unitId("std/"));

        for (String invalid : List.of(
                "std/../secret", "std/./core", "std/a/../core",
                "std//core", "std/", "std/\\\\core",
                "std/core?x", "std/%2e%2e/core", "std/one//two",
                "std/core" + (char) 0,
                "std/" + "verylongsegment".repeat(400),
                "std/" + "nested/".repeat(33) + "core")) {
            assertThrows(IllegalArgumentException.class,
                    () -> CoreLibrarySources.unitId(invalid), invalid);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> CoreLibrarySources.source("std/not-a-real-module"));
        assertEquals(CoreLibrarySources.unitId("std/core"),
                CoreLibrarySources.unitId("std/core.ores"));
        assertEquals(CoreLibrarySources.source("std/core"),
                CoreLibrarySources.source("std/core.ores"));
        assertTrue(CoreLibrarySources.source("std/testing").contains("define module testing"));
        assertFalse(CoreLibrarySources.packagedSourcePaths().contains("phantom.ores"));
    }
}

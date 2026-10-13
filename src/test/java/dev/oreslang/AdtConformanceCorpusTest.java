package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Single source-of-truth ADT corpus shared with the LLVM conformance plan.
 * The manifest explicitly says llvm=pending: running this Java test does
 * NOT claim that LLVM supports these cases.
 */
final class AdtConformanceCorpusTest {
    private static final Path ROOT = Path.of("contracts/v2/adt-conformance");

    @TestFactory
    Stream<DynamicTest> corpus() throws Exception {
        TomlParseResult manifest = Toml.parse(Files.readString(
                ROOT.resolve("cases.toml"), StandardCharsets.UTF_8));
        assertFalse(manifest.hasErrors(), () -> manifest.errors().toString());
        assertEquals("oreslang.adt.conformance.v2", manifest.getString("schema"));
        assertEquals(1L, manifest.getLong("version"));

        TomlArray cases = manifest.getArray("case");
        assertNotNull(cases, "ADT corpus must contain cases");
        assertTrue(cases.size() >= 14, "the baseline corpus must not silently shrink");
        Set<String> identifiers = new HashSet<>();
        for (int index = 0; index < cases.size(); index++) {
            TomlTable entry = cases.getTable(index);
            assertNotNull(entry);
            assertTrue(identifiers.add(entry.getString("id")),
                    "duplicate ADT conformance case: " + entry.getString("id"));
            assertEquals("required", entry.getString("java"));
            assertEquals("pending", entry.getString("llvm"),
                    "LLVM may only be promoted after separately verified execution");
        }

        return IntStream.range(0, cases.size()).mapToObj(index -> {
            TomlTable entry = cases.getTable(index);
            String id = entry.getString("id");
            return DynamicTest.dynamicTest(id, () -> {
                String sourceFile = entry.getString("source");
                assertNotNull(sourceFile, "missing fixture path for " + id);
                // Reject path traversal and directories; fixture identities
                // must be stable and no test case may read outside ROOT.
                assertTrue(sourceFile.matches("[a-z0-9-]+[.]ores"),
                        "invalid ADT fixture path " + sourceFile);
                String source = Files.readString(ROOT.resolve(sourceFile),
                        StandardCharsets.UTF_8);
                if ("reject".equals(entry.getString("kind"))) {
                    IllegalArgumentException failure = assertThrows(
                            IllegalArgumentException.class,
                            () -> TypeChecker.check(Parser.parse(source)),
                            id + " should fail closed at compile time");
                    String diagnostic = entry.getString("diagnostic_contains");
                    assertNotNull(diagnostic);
                    assertTrue(failure.getMessage().contains(diagnostic),
                            () -> id + ": expected diagnostic containing '" + diagnostic
                                    + "' but got " + failure);
                } else if ("run".equals(entry.getString("kind"))) {
                    assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                            "source-level type/ownership check: " + id);
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    Source guest = Source.newBuilder(
                            OresLanguage.ID, source, sourceFile)
                            .mimeType(OresLanguage.MIME_TYPE).build();
                    try (Context context = Context.newBuilder(OresLanguage.ID)
                            .allowAllAccess(false).out(output).build()) {
                        context.eval(guest);
                    }
                    String expected = entry.getString("expected_stdout");
                    assertNotNull(expected);
                    assertEquals(expected.replace("\r\n", "\n"),
                            output.toString(StandardCharsets.UTF_8)
                                    .replace("\r\n", "\n"),
                            "ADT guest execution differs from the shared corpus: " + id);
                } else {
                    fail("unknown ADT conformance kind for " + id);
                }
            });
        });
    }
}

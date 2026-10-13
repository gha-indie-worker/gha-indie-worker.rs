package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Legacy bare matches are exclusive, unlike source-ordered match ... over.
 * Multiple explicit arms may not match the same input at runtime.
 */
final class ExclusiveAdtMatchSafetyTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejectsOverlap(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("overlapping exclusive match arms"),
                error.toString());
    }

    @Test
    void legacyBoolCasesAreStaticallyDisjoint() {
        accepts("""
                fnc classify(bool input): int {
                    match input
                        true -> { return 1; }
                        false -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void legacyNominalEnumCasesHaveDistinctTags() {
        accepts("""
                define enum State as
                    Waiting
                    Done
                end
                fnc classify(State input): int {
                    match input
                        State.Waiting -> { return 1; }
                        State.Done -> { return 2; }
                    end
                }
                """);
    }

    @Test
    void exclusiveOverlappingGuardedPatternsAreRejected() {
        rejectsOverlap("""
                fnc classify(bool input, bool condition): int {
                    match input
                        true when condition -> { return 1; }
                        true when condition -> { return 2; }
                        false -> { return 0; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void guardNamesFromDifferentTuplePositionsAreNotProofOfDisjointness() {
        // For input (5, 20) BOTH guards hold. Although both arms call their
        // binding 'x', each arm binds a different tuple component.
        rejectsOverlap("""
                fnc classify(Tuple[int, int] pair): int {
                    match pair;
                        (x, _) when x < 10 -> { return 1; }
                        (_, x) when x > 10 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void IdenticalPatternsWithProvenDisjointGuardRangesCanCoexist() {
        accepts("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 0 -> { return 1; }
                        State.Count(x) when x >= 0 -> { return 2; }
                        else -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void canonicalOrderedMatchesAllowOverlappingGuardedArms() {
        accepts("""
                fnc classify(bool input, bool condition): int {
                    match input over
                        on true when condition -> { return 1; }
                        on true when condition -> { return 2; }
                        on false -> { return 0; }
                        on _ -> { return 3; }
                    end
                }
                """);
    }

    @Test
    void wideGuardBoundsCannotBecomeDisjointThroughDoubleRounding() {
        // 9007199254740993 rounds to 9007199254740992 as double.
        // The guards overlap for the exact integer 9007199254740992.
        rejectsOverlap("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 9007199254740993 -> { return 1; }
                        State.Count(x) when x >= 9007199254740992 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void ExactRepresentableGuardBoundRemainsProvablyDisjoint() {
        accepts("""
                define enum State as
                    Count: int
                end
                fnc classify(State input): int {
                    match input
                        State.Count(x) when x < 9007199254740992 -> { return 1; }
                        State.Count(x) when x >= 9007199254740992 -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void mixedIntegerAndFloatingLiteralsAreNotExclusive() {
        // Runtime treats 1L and 1.0 as numerically equal.
        rejectsOverlap("""
                fnc classify(int value, bool guard): int {
                    match value;
                        1 when guard -> { return 1; }
                        1.0 when guard -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void differentMixedNumericLiteralsRemainProvablyDisjoint() {
        accepts("""
                fnc classify(int value, bool guard): int {
                    match value;
                        1 when guard -> { return 1; }
                        2.0 when guard -> { return 2; }
                        else -> { return 0; }
                    end
                }
                """);
    }

    @Test
    void orderedMatchRejectsNumericallyEquivalentDeadLiteralArm() {
        String source = """
                fnc classify(int value): int {
                    match value over
                        on 1 -> { return 1; }
                        on 1.0 -> { return 2; }
                        on _ -> { return 0; }
                    end
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("unreachable match arm"),
                error.toString());
    }

    @Test
    void guestRuntimeUsesFirstMatchingOrderedArmAndFallsThroughFalseGuards() throws Exception {
        String program = """
                fnc classify(bool value, bool gate): int {
                    match value over
                        on true when gate -> { return 1; }
                        on true when gate -> { return 2; }
                        on false -> { return 0; }
                        on _ -> { return 3; }
                    end
                }
                pub fnc main(): void {
                    stdio.println(classify(true, true));
                    stdio.println(classify(true, false));
                    stdio.println(classify(false, true));
                    return;
                }
                """;
        accepts(program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source guest = Source.newBuilder(OresLanguage.ID,
                program, "ordered-match-guard-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(guest);
        }
        assertEquals("1\\n3\\n0",
                output.toString(StandardCharsets.UTF_8)
                        .strip().replace("\\r\\n", "\\n"));
    }

    @Test
    void exclusiveMatchesHaveAtMostOneFallbackArm() {
        String source = """
                fnc classify(bool input): int {
                    match input
                        true -> { return 1; }
                        else -> { return 0; }
                        else -> { return 2; }
                    end
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("multiple fallback"),
                failure.toString());
    }
}

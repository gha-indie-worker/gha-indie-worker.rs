package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Closed sum/product matching: source typing, exhaustiveness, and guest runtime. */
final class AlgebraicDataTypeMatchTest {
    private static final String STATUS = """
            define enum Status as
                Loading
                Active: int
                Failed
            end
            """;

    private static IllegalArgumentException rejects(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nominalEnumMatchChecksEveryCaseAndBindsPayload() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(STATUS + """
                fnc classify(Status value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(inner) -> { return inner; }
                    on Status.Failed -> { return -1; }
                  end
                }
                """)));
    }

    @Test
    void exhaustiveMatchRejectsMissingOrPartiallyMatchedEnumCases() {
        assertTrue(rejects(STATUS + """
                fnc bad(Status value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(inner) -> { return inner; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));

        assertTrue(rejects(STATUS + """
                fnc bad(Status value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(7) -> { return 7; }
                    on Status.Failed -> { return -1; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
    }

    @Test
    void duplicateUnreachableAndWrongDomainCasesFailClosed() {
        assertTrue(rejects(STATUS + """
                fnc bad(Status value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(_) -> { return 1; }
                    on Status.Active(7) -> { return 7; }
                    on Status.Failed -> { return -1; }
                  end
                }
                """).getMessage().contains("unreachable match arm"));

        String other = """
                define enum Other as
                    Ready
                end
                """;
        assertTrue(rejects(STATUS + other + """
                fnc bad(Status value): int {
                  match value over
                    on Other.Ready -> { return 0; }
                    on _ -> { return 1; }
                  end
                }
                """).getMessage().contains("not in match subject domain"));

        assertTrue(rejects(STATUS + """
                fnc bad(Status value): int {
                  match value over
                    on Status.Active -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """).getMessage().contains("expects 1 payload pattern"));
    }

    @Test
    void optionAndResultPayloadLiteralsAreNotExhaustive() {
        assertTrue(rejects("""
                fnc bad(Option<int> value): int {
                  match value over
                    on Some(7) -> { return 7; }
                    on None -> { return 0; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
        assertTrue(rejects("""
                fnc bad(Result<int, string> value): int {
                  match value over
                    on Ok(number) -> { return number; }
                    on Err("only") -> { return 0; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc good(Result<int, string> value): int {
                  match value over
                    on Ok(number) -> { return number; }
                    on Err(_) -> { return 0; }
                  end
                }
                """)));
    }

    @Test
    void productDestructuringNeedsCoverageOfEveryMember() {
        String decls = """
                define enum Answer as
                    Pair: Tuple[bool, string]
                    Unknown
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(decls + """
                fnc good(Answer answer): int {
                  match answer over
                    on Answer.Pair as (flag, text) -> { return 1; }
                    on Answer.Unknown -> { return 0; }
                  end
                }
                """)));
        assertTrue(rejects(decls + """
                fnc bad(Answer answer): int {
                  match answer over
                    on Answer.Pair as (true, text) -> { return 1; }
                    on Answer.Unknown -> { return 0; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
    }

    @Test
    void matchPatternSpreadInvalidatesStaleExhaustiveMatch() {
        String decls = STATUS + """
                define match pattern Messages as
                    ...Status
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(decls + """
                fnc good(Messages value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(_) -> { return 1; }
                    on Status.Failed -> { return 2; }
                  end
                }
                """)));
        String expanded = decls.replace("Failed\n", "Failed\n    Suspended\n");
        assertTrue(rejects(expanded + """
                fnc stale(Messages value): int {
                  match value over
                    on Status.Loading -> { return 0; }
                    on Status.Active(_) -> { return 1; }
                    on Status.Failed -> { return 2; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
    }

    @Test
    void boundedFiniteDomainsProveCombinedPartialCoverage() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc option(Option<bool> value): int {
                  match value over
                    on Some(true) -> { return 1; }
                    on Some(false) -> { return 0; }
                    on None -> { return -1; }
                  end
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc result(Result<bool, bool> value): int {
                  match value over
                    on Ok(true) -> { return 1; }
                    on Ok(false) -> { return 2; }
                    on Err(true) -> { return 3; }
                    on Err(false) -> { return 4; }
                  end
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc product(Tuple[bool, bool] value): int {
                  match value over
                    on (true, _) -> { return 1; }
                    on (false, true) -> { return 2; }
                    on (false, false) -> { return 3; }
                  end
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define enum Tiny as
                    Waiting
                    State: bool
                end
                fnc classify(Tiny value): int {
                  match value over
                    on Tiny.Waiting -> { return 0; }
                    on Tiny.State(true) -> { return 1; }
                    on Tiny.State(false) -> { return 2; }
                  end
                }
                """)));
    }

    @Test
    void guardedFiniteCaseCannotDischargeAnExhaustiveProof() {
        assertTrue(rejects("""
                fnc partial(Option<bool> value): int {
                  match value over
                    on Some(true) when true -> { return 1; }
                    on Some(false) -> { return 2; }
                    on None -> { return 0; }
                  end
                }
                """).getMessage().contains("non-exhaustive match"));
    }

    @Test
    void combinedFiniteCoverageMakesLaterArmUnreachable() {
        assertTrue(rejects("""
                fnc dead(Option<bool> value): int {
                  match value over
                    on Some(true) -> { return 1; }
                    on Some(false) -> { return 2; }
                    on Some(_) -> { return 3; }
                    on None -> { return 0; }
                  end
                }
                """).getMessage().contains("unreachable match arm"));
        assertTrue(rejects("""
                fnc dead(bool value): int {
                  match value over
                    on true -> { return 1; }
                    on false -> { return 0; }
                    on _ -> { return 2; }
                  end
                }
                """).getMessage().contains("unreachable match arm"));
    }

    @Test
    void maximumFiniteBooleanProductProofIsCompleteAndDetectsDeadArms() {
        // 12 bools = 4096 distinct values, exactly the proof quota.
        // The two leading-bit patterns partition the entire product.
        String members = String.join(", ", java.util.Collections.nCopies(12, "bool"));
        String suffix = ", " + String.join(", ", java.util.Collections.nCopies(11, "_"));
        String prefix = "fnc classify(Tuple[" + members + "] input): int {\\n"
                + "match input over\\n";
        String arms = "on (true" + suffix + ") -> { return 1; }\\n"
                + "on (false" + suffix + ") -> { return 0; }\\n";
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                prefix + arms + "end\\n}")));
        assertTrue(rejects(prefix + arms
                + "on _ -> { return -1; }\\nend\\n}")
                .getMessage().contains("unreachable match arm"));
    }

    @Test
    void overflowedFiniteDomainCannotProveAFalseExhaustivenessClaim() {
        // 13 bools = 8192 values; do not truncate to the first 4096.
        String members = String.join(", ", java.util.Collections.nCopies(13, "bool"));
        String suffix = ", " + String.join(", ", java.util.Collections.nCopies(12, "_"));
        String prefix = "fnc classify(Tuple[" + members + "] input): int {\\n"
                + "match input over\\n";
        String arms = "on (true" + suffix + ") -> { return 1; }\\n"
                + "on (false" + suffix + ") -> { return 0; }\\n";
        assertTrue(rejects(prefix + arms + "end\\n}")
                .getMessage().contains("non-exhaustive match"));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(
                prefix + arms + "on _ -> { return -1; }\\nend\\n}")));
    }

    @Test
    void oversizedMatchArmMatrixFailsBeforeQuadraticProofWork() {
        StringBuilder source = new StringBuilder(
                "fnc classify(bool input): int {\\nmatch input over\\n");
        for (int i = 0; i <= 4096; i++) {
            source.append("on true -> { return 1; }\\n");
        }
        source.append("end\\n}");
        // Bound the parser itself; accepting 100k arms before TypeChecker
        // notices the quota would still be an untrusted-source DoS.
        IllegalArgumentException parsed = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source.toString()));
        assertTrue(parsed.getMessage().contains("match exceeds 4096 arms"),
                parsed.toString());
    }

    @Test
    void guestRuntimeDispatchAgreesWithFiniteExhaustivenessProof() throws Exception {
        String sourceText = """
                define enum Tiny as
                    Waiting
                    State: bool
                end
                fnc enumCase(Tiny value): int {
                  match value over
                    on Tiny.Waiting -> { return 0; }
                    on Tiny.State(true) -> { return 1; }
                    on Tiny.State(false) -> { return 2; }
                  end
                }
                fnc optionCase(Option<bool> value): int {
                  match value over
                    on Some(true) -> { return 3; }
                    on Some(false) -> { return 4; }
                    on None -> { return 5; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(enumCase(Tiny.Waiting()));
                  stdio.println(enumCase(Tiny.State(true)));
                  stdio.println(enumCase(Tiny.State(false)));
                  stdio.println(optionCase(Some(true)));
                  stdio.println(optionCase(Some(false)));
                  stdio.println(optionCase(None));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "finite-adt-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("0\\n1\\n2\\n3\\n4\\n5".replace("\\n", "\n"),
                output.toString(StandardCharsets.UTF_8).strip().replace("\r\n", "\n"));
    }

    @Test
    void guestRuntimeDistinguishesIdenticalPayloadShapes() throws Exception {
        String sourceText = """
                define enum Signal as
                    Accepted: int
                    Rejected: int
                end
                fnc classify(Signal value): int {
                  match value over
                    on Signal.Accepted as number -> { return number; }
                    on Signal.Rejected(number) -> { return number + 100; }
                  end
                }
                pub fnc main(): void {
                  stdio.println(classify(Signal.Accepted(7)));
                  stdio.println(classify(Signal.Rejected(7)));
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(sourceText)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, "adt-case-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertEquals("7\n107", text.strip().replace("\r\n", "\n"));
    }
}

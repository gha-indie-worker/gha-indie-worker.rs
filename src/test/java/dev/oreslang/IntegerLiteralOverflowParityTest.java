package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class IntegerLiteralOverflowParityTest {

    private static void rejectsOverflow(String source, String offendingLexeme) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains("integer literal overflows signed 64-bit"),
                "Overflow must have a stable Oreslang diagnostic: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(offendingLexeme),
                "Diagnostic must retain the offending lexeme: " + failure.getMessage());
    }

    @Test
    void expressionLiteralsRejectI64OverflowWithStableDiagnostic() {
        rejectsOverflow("pub fnc main(): int { return 9223372036854775808; }", "9223372036854775808");
        rejectsOverflow("pub fnc main(): int { return 9_223_372_036_854_775_808; }", "9_223_372_036_854_775_808");
    }

    @Test
    void matchPatternLiteralsUseTheSameI64OverflowContract() {
        rejectsOverflow("""
                pub fnc main(): int {
                  match 1 over
                    on 9223372036854775808 -> { return 42; }
                  end
                  return 0;
                }
                """, "9223372036854775808");
    }

    @Test
    void signedI64MaximumAndSeparatorsRemainAccepted() {
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): int { return 9223372036854775807; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): int { return 9_223_372_036_854_775_807; }"));
    }
}

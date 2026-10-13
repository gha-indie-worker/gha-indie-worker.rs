package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class IntegerLiteralParityTest {
    @Test
    void portableSignedI64BoundaryAdmitsMaxAndSeparators() {
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): int { return 9223372036854775807; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc main(): int { return 9_223_372_036_854_775_807; }"));
    }

    @Test
    void oversizedLiteralsAreSourcePositionedSemanticErrors() {
        for (String literal : new String[] {
                "9223372036854775808",
                "9_223_372_036_854_775_808",
                "18446744073709551615",
                "9999999999999999999999999999999999999"
        }) {
            String source = "pub fnc main(): int { return " + literal + "; }";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(error.getMessage().startsWith("Oreslang parse error at 1:"),
                    error::getMessage);
            assertTrue(error.getMessage().contains("integer literal overflows signed 64-bit"),
                    error::getMessage);
        }
    }

    @Test
    void malformedDecimalSeparatorsRejectInsteadOfSilentlyNormalizing() {
        for (String literal : new String[] {"1__2", "1_", "1___2"}) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc main(): int { return " + literal + "; }"));
            assertTrue(error.getMessage().contains("malformed integer separator"),
                    error::getMessage);
        }
    }

    @Test
    void literalMatchPatternsUseSameI64RangeCheck() {
        String source = """
                pub fnc main(): int {
                  match 0 over
                    on 9223372036854775808 -> { return 1; }
                    on _ -> { return 0; }
                  end
                  return 0;
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains("integer literal overflows signed 64-bit"),
                error::getMessage);
    }
}

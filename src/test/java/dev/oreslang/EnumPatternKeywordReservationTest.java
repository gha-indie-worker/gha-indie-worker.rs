package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First-stage fail-closed lexical gate for proposed enum and named pattern declarations.
 *
 * These tests MUST change when full declaration parsing, type checking and execution land.
 * Reserved syntax rejection is not runtime feature support.
 */
final class EnumPatternKeywordReservationTest {
    @Test
    void keywordsAreNotOrdinaryIdentifiers() {
        assertEquals(Token.Type.ENUM, new Lexer("enum").scan().getFirst().type());
        assertEquals(Token.Type.PATTERN, new Lexer("pattern").scan().getFirst().type());
        assertNotEquals(Token.Type.IDENT, new Lexer("enum").scan().getFirst().type());
        assertNotEquals(Token.Type.IDENT, new Lexer("pattern").scan().getFirst().type());
    }

    @Test
    void futureDeclarationsFailClosedInsteadOfBeingSilentlyAccepted() {
        String[] declarations = {
            "define enum Status as Pending Running end",
            "define pattern Accepted as string | int end",
            "define module x as define enum Status as Pending end end",
            "define module x as define pattern Accepted as string end end"
        };
        for (String source : declarations) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(failure.getMessage().contains("reserved but not yet implemented"),
                    failure::getMessage);
        }
    }

    @Test
    void reservedWordsCannotBeFunctionNames() {
        for (String keyword : new String[] {"enum", "pattern"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc " + keyword + "(): int { return 42; }"));
        }
    }
}

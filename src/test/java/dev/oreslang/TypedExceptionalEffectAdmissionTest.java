package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TypedExceptionalEffectAdmissionTest {
    @Test
    void exceptionalEffectNamesHaveDedicatedTokens() {
        List<Token.Type> tokens = new Lexer("throw raise panic recover").scan().stream()
                .map(Token::type).toList();
        assertEquals(List.of(Token.Type.THROW, Token.Type.RAISE,
                Token.Type.PANIC, Token.Type.RECOVER, Token.Type.EOF), tokens);
    }

    @Test
    void unsupportedEffectsRejectBeforeSourceExecution() {
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            String source = "pub fnc main(): int { " + effect + " 1; return 0; }";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), effect);
            assertTrue(failure.getMessage().contains("typed exceptional effect '" + effect + "'"),
                    failure::getMessage);
            assertTrue(failure.getMessage().contains("not yet enabled"),
                    failure::getMessage);
        }
    }

    @Test
    void reservedKeywordsCannotMasqueradeAsOrdinaryIdentifiers() {
        for (String effect : List.of("throw", "raise", "panic", "recover")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("pub fnc " + effect + "(): int { return 1; }"),
                    effect);
        }
    }

    @Test
    void UnrelatedFunctionNamesRemainValid() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc throwable(): int { return 42; }
                fnc recovery(): int { return throwable(); }
                """));
    }
}

package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ContextualCaseWhenIdentifierTest {
    @Test
    void lexerLeavesCaseAndWhenAsIdentifiers() {
        var tokens = new Lexer("case when").scan();

        assertEquals(Token.Type.IDENT, tokens.get(0).type());
        assertEquals("case", tokens.get(0).lexeme());
        assertEquals(Token.Type.IDENT, tokens.get(1).type());
        assertEquals("when", tokens.get(1).lexeme());
    }

    @Test
    void caseAndWhenCanBeParametersBindingsAndExpressions() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc sum(int case, int when): int {
                  return case + when;
                }

                fnc locals(): int {
                  const case = 1;
                  let when = 2;
                  when = when + case;
                  return when;
                }
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void selectAndSwitchStillRecognizeContextualArmKeywords() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc selectNames(Channel<int> caseCh, Channel<int> whenCh): void {
                  do select {
                    when readch caseCh: val case {
                      stdio.println(case);
                    }
                    case readch whenCh: val when {
                      stdio.println(when);
                    }
                  }
                  return;
                }

                fnc classify(int case, int when): void {
                  switch case;
                    case 1 -> {
                      stdio.println(when);
                    }
                    default -> {
                    }
                  end
                  return;
                }
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void matchGuardWhenRemainsContextualWithoutReservingTheIdentifier() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc guarded(int when): void {
                  match when;
                    1 when when eq 1 -> {
                      return;
                    }
                    else -> {
                      return;
                    }
                  end
                }
                """));

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }
}

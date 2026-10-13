package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectArmWaitModeSyntaxTest {
    @Test
    void rejectsPerArmNonblockingAndImmediateReadWrite() {
        for (String selectHeader : new String[]{
                "select", "do select", "nb select", "do nb select", "try select"}) {
            for (String armKeyword : new String[]{"when", "case"}) {
                for (String modifier : new String[]{"nb", "try"}) {
                    for (String operation : new String[]{
                            "readch messages: val msg",
                            "writech messages, 7:"}) {
                        String source = """
                                fnc example(Channel<int> messages): void {
                                  %s {
                                    %s %s %s {
                                      return;
                                    }
                                  }
                                  return;
                                }
                                """.formatted(selectHeader, armKeyword, modifier, operation);
                        IllegalArgumentException problem = assertThrows(
                                IllegalArgumentException.class,
                                () -> Parser.parse(source), source);
                        assertTrue(problem.getMessage().contains(
                                "select arm cannot use '" + modifier + " "
                                        + (operation.startsWith("readch") ? "readch" : "writech") + "'"),
                                () -> source + "\n" + problem.getMessage());
                        assertTrue(problem.getMessage().contains("enclosing select"),
                                problem::getMessage);
                    }
                }
            }
        }
    }

    @Test
    void rejectsPerArmModifierInBindingFirstReadSyntax() {
        for (String header : new String[]{"select", "do select", "nb select", "do nb select", "try select"}) {
            for (String modifier : new String[]{"nb", "try"}) {
                String source = """
                        fnc example(Channel<int> messages): void {
                          %s {
                            when val msg = %s readch(messages) -> {
                              return;
                            }
                          }
                          return;
                        }
                        """.formatted(header, modifier);
                IllegalArgumentException problem = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source));
                assertTrue(problem.getMessage().contains(
                        "select arm cannot use '" + modifier + " readch'"),
                        problem::getMessage);
            }
        }
    }

    @Test
    void acceptsUnqualifiedReadArmsAndStandaloneNonblockingRead() {
        var typed = assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc receive(Channel<int> messages): void {
                  select {
                    when readch messages: val msg {
                      stdio.println(msg);
                    }
                  }
                  do select {
                    when val msg = readch(messages) -> {
                      stdio.println(msg);
                    }
                  }
                  return;
                }

                actor fnc later(): void {
                  val Channel<int> messages = Channel.new<int>(1);
                  do nb select {
                    when readch messages: val msg {
                      stdio.println(msg);
                    }
                  }
                  return;
                }

                fnc independent(Channel<int> messages): Future<int> {
                  return nb readch messages;
                }
                """)));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void permitsStandaloneNbReadchInsideSelectedArmBody() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc example(Channel<int> messages, Channel<int> other): void {
                  select {
                    when readch messages: val msg {
                      val Future<int> pending = nb readch other;
                      stdio.println(msg);
                    }
                  }
                  return;
                }
                """));
    }
}

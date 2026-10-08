package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectArmControlBoundaryTest {
    @Test
    void historicalSelectCanStillExitTheEnclosingCallable() throws Exception {
        String source = """
                pub fnc compute(): int {
                  select {
                    default: {
                      return 7;
                    }
                  }
                  return 99;
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("7", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void doSelectCannotBreakEnclosingLoop() {
        String source = """
                pub fnc main(): void {
                  loop {
                    do select {
                      default: {
                        break;
                      }
                    }
                  }
                }
                """;
        var problem = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(problem.getMessage().contains("break"), problem::getMessage);
    }

    @Test
    void doSelectCannotContinueEnclosingLoop() {
        String source = """
                pub fnc main(): void {
                  loop {
                    do select {
                      default: {
                        continue;
                      }
                    }
                  }
                }
                """;
        var problem = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(problem.getMessage().contains("continue"), problem::getMessage);
    }

    @Test
    void doSelectCanBreakAnInnerLoop() throws Exception {
        String source = """
                pub fnc main(): void {
                  do select {
                    default: {
                      loop {
                        break;
                      }
                      stdio.stdout.write("done");
                    }
                  }
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("done", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void doNbSelectRequiresActorExecutionDomain() {
        String source = """
                pub fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  do nb select {
                    case readch input: val value {
                      return value;
                    }
                  }
                  return;
                }
                """;
        var problem = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(problem.getMessage().contains("actor"), problem::getMessage);
    }

    @Test
    void doNbSelectReturnsAreArmLocalInActor() {
        String source = """
                actor fnc later(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val int offset = 3;
                  do nb select {
                    case readch input: val value {
                      return value + offset;
                    }
                  }
                  stdio.stdout.write(offset);
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 1);
    }
}

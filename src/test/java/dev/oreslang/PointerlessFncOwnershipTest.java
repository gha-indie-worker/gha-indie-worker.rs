package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PointerlessFncOwnershipTest {

    @Test
    void firstClassFncCallsTemporarilyReadBorrowNonCopyArguments() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Box as
                  pub let int value = 7;
                end

                fnc ok(): int {
                  let mut Box box = new Box();
                  val Fnc<Box, int> read = |value| -> {
                    return value.value;
                  };
                  val int before = read(box);
                  box.value = 9;
                  val int after = read(box);
                  return before + after;
                }
                """)));
    }

    @Test
    void storedRtBorrowBlocksMutationUntilItsScopeEnds() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val view = rt borrow box;
                          box.value = 9;
                          stdio.println(view.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error.getMessage());
    }

    @Test
    void rtTakeTransfersOwnershipAndInvalidatesTheSourceBinding() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val Box moved = rt take box;
                          stdio.println(box.value);
                          stdio.println(moved.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("moved"), error.getMessage());
    }

    @Test
    void rtCopyAndRtShareAreSafeForCopyValues() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val int source = 7;
                  val int copied = rt copy source;
                  val int shared = rt share source;
                  stdio.stdout.write(copied + shared);
                  return;
                }
                """);
        assertEquals("14", output);
    }

    @Test
    void moveOnlyCopyAndShareFailClosedUntilTheirFullContractsConverge() {
        IllegalArgumentException copied = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val other = rt copy box;
                          stdio.println(other.value);
                          return;
                        }
                        """)));
        assertTrue(copied.getMessage().contains("rt copy"), copied.getMessage());

        IllegalArgumentException shared = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub let int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val alias = rt share box;
                          stdio.println(alias.value);
                          return;
                        }
                        """)));
        assertTrue(shared.getMessage().contains("rt share"), shared.getMessage());
    }

    @Test
    void groupedBorrowIsNotATupleAndExplicitSingletonUnpacks() throws Exception {
        for (String binding : new String[] {
                "val borrowed = (rt borrow values);",
                "val (borrowed) = tuple (rt borrow values);"}) {
            assertEquals("1", run("""
                    pub routine main(): void {
                      val Array<int> values = new Array<int>();
                      values.add(7);
                      %s
                      stdio.stdout.write(borrowed.size);
                      return;
                    }
                    """.formatted(binding)));
            var error = assertThrows(IllegalArgumentException.class, () ->
                    TypeChecker.check(Parser.parse("""
                    fnc bad(): void {
                      val Array<int> values = new Array<int>();
                      %s
                      values.add(8);
                      stdio.println(borrowed.size);
                      return;
                    }
                    """.formatted(binding))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
    }

    @Test
    void explicitTupleArityAndGroupingHaveDistinctAstNodes() {
        var program = Parser.parse("""
                fnc syntax(): void {
                  val grouped = (rt borrow values);
                  val singleton = tuple (rt borrow values);
                  val empty = tuple ();
                  val pair = tuple (1, 2);
                  return;
                }
                """);
        var function = (dev.oreslang.ast.Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        var grouped = (dev.oreslang.ast.Ast.BindingStmt) function.body().get(0);
        assertInstanceOf(dev.oreslang.ast.Ast.RuntimeCallExpr.class, grouped.initializer());
        for (int i = 1; i <= 3; i++) {
            var binding = (dev.oreslang.ast.Ast.BindingStmt) function.body().get(i);
            var tuple = assertInstanceOf(dev.oreslang.ast.Ast.TupleExpr.class, binding.initializer());
            assertEquals(new int[] {1, 0, 2}[i - 1], tuple.elements().size());
        }
    }

    @Test
    void tupleBorrowCannotEscapeOrSurviveCooperation() {
        for (String operation : new String[] {"return borrowed;", "rt cooperate; return;"}) {
            var error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    fnc bad(): %s {
                      val Array<int> values = new Array<int>();
                      val (borrowed) = tuple (rt borrow values);
                      %s
                    }
                    """.formatted(operation.startsWith("return") ? "borrow Array<int>" : "void", operation))));
            assertTrue(error.getMessage().contains("borrow"), error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Array<int> values = new Array<int>();
                  val packed = tuple (rt borrow values);
                  return;
                }
                """)));
    }

    @Test
    void keywordMutableBorrowPreservesExclusiveAccess() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc update(borrow mut Array<int> values): void { values.add(7); return; }
                fnc ok(): void {
                  val Array<int> values = new Array<int>();
                  update(rt borrow mut values);
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Array<int> values = new Array<int>();
                  val first = rt borrow mut values;
                  val second = rt borrow values;
                  return;
                }
                """)));
    }

    private String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("ores")
                .allowAllAccess(true)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder("ores", code, "pointerless-fnc-ownership.ores").build());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

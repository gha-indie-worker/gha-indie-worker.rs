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
                  let Box box = new Box();
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

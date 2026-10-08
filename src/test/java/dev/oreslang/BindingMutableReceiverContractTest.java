package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BindingMutableReceiverContractTest {
    private static final String PREFIX = """
            define class Counter as
              pub let int value = 0;

              pub bump(mut self)(): int {
                self.value = self.value + 1;
                return self.value;
              }
            end

            """;

    private void accepts(String body) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PREFIX + """
                fnc subject(): int {
                """ + body + """
                }
                """)));
    }

    private void rejects(String body) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(PREFIX + """
                fnc subject(): int {
                """ + body + """
                }
                """)));
    }

    @Test void valAndConstMutMayInvokeMutableReceiverWithoutRebinding() {
        accepts("""
                  val Counter counter = new Counter();
                  return counter.bump();
                """);
        accepts("""
                  const mut Counter counter = new Counter();
                  return counter.bump();
                """);
    }

    @Test void constAndPlainLetCannotInvokeMutableReceiver() {
        rejects("""
                  const Counter counter = new Counter();
                  return counter.bump();
                """);
        rejects("""
                  let Counter counter = new Counter();
                  return counter.bump();
                """);
    }

    @Test void letMutMayMutateAndRebind() {
        accepts("""
                  let mut Counter counter = new Counter();
                  counter.bump();
                  counter = new Counter();
                  return counter.bump();
                """);
    }

    @Test void readonlyIntermediateFieldBlocksDeepMutation() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(PREFIX + """
                define class Holder as
                  pub const Counter counter = new Counter();
                end

                fnc bad(): int {
                  val Holder holder = new Holder();
                  return holder.counter.bump();
                }
                """)));
    }

    @Test void mutableIntermediateFieldAllowsDeepMutationForMutableRoot() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PREFIX + """
                define class Holder as
                  pub val Counter counter = new Counter();
                end

                fnc ok(): int {
                  val Holder holder = new Holder();
                  return holder.counter.bump();
                }
                """)));
    }
}

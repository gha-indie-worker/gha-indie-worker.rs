package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TrapOptionSemanticsTest {

    @Test
    void trapCallsTypeAsOptionAndNestedOptionIsNotFlattened() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                trap fnc value(): int {
                  return 7;
                }

                trap fnc maybe(bool present): Option<int> {
                  if present; then
                    return Some(9);
                  fi
                  return None;
                }

                trap fnc done(): void {
                  return;
                }

                fnc use(): void {
                  val Option<int> one = value();
                  val Option<Option<int>> nested = maybe(true);
                  val Option<void> finished = done();
                  return;
                }
                """)));
    }

    @Test
    void trapReturnsSomeOnSuccessAndNoneOnOrdinaryRuntimeFailure() throws Exception {
        String program = """
                define class Animal as
                end

                define class Dog extends Animal as
                end

                define class Cat extends Animal as
                end

                trap fnc as_dog(Animal animal): Dog {
                  return animal as Dog;
                }

                trap fnc maybe(bool present): Option<int> {
                  if present; then
                    return Some(9);
                  fi
                  return None;
                }

                trap fnc done(): void {
                  return;
                }

                pub fnc main(): void {
                  val Option<Dog> good = as_dog(new Dog());
                  val Option<Dog> bad = as_dog(new Cat());
                  val Option<Option<int>> nested_some = maybe(true);
                  val Option<Option<int>> nested_none = maybe(false);
                  val Option<void> finished = done();

                  stdio.stdout.write(good.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(bad.is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_some.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested_none.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(finished.is_some());
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:true:9:true:true", run(program));
    }

    @Test
    void panicStillBypassesTrap() throws Exception {
        String program = """
                trap fnc panics(): int {
                  val Option<int> missing = None;
                  return missing.unwrap();
                }

                pub fnc main(): void {
                  val Option<int> ignored = panics();
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        PolyglotException thrown = assertThrows(PolyglotException.class, () -> run(program));
        assertTrue(thrown.getMessage().contains("Option::unwrap"));
    }

    @Test
    void unsupportedSuspendingTrapFormsFailClosed() {
        IllegalArgumentException async = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        async trap fnc later(): int {
                          return 1;
                        }
                        """));
        assertTrue(async.getMessage().contains("async trap"));

        IllegalArgumentException generator = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        generator trap fnc values(): int {
                          yield 1;
                          return;
                        }
                        """));
        assertTrue(generator.getMessage().contains("trap generator"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "trap-option.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}

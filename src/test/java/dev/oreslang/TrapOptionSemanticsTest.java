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


    @Test
    void everySynchronousTrappedResultRejectsAssignmentToBareSuccessType() {
        String declarations = """
                trap fnc number(): int { return 7; }
                trap fnc truth(): bool { return true; }
                trap fnc done(): void { return; }
                trap fnc optional(): Option<int> { return None; }
                """;
        String[] invalidBindings = {
                "val int unwrapped = number();",
                "val bool unwrapped = truth();",
                "val int unwrapped = optional();",
                "val Option<int> flattened = optional();",
                "val Option<int> wrong_void = done();"
        };
        for (String binding : invalidBindings) {
            String source = declarations + "fnc check(): void { " + binding + " return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), binding);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(declarations + """
                fnc check(): void {
                  val Option<int> number_value = number();
                  val Option<bool> truth_value = truth();
                  val Option<void> finished = done();
                  val Option<Option<int>> nested = optional();
                  return;
                }
                """));
    }

    @Test
    void trappedFunctionValueMustPreserveOptionReturn() throws Exception {
        String source = """
                trap fnc value(int x): int { return x + 1; }
                pub fnc main(): void {
                  val Fnc<int, Option<int>> callback = value;
                  val Option<int> direct = value(9);
                  val Option<int> via_callback = callback(9);
                  stdio.stdout.write(direct.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(via_callback.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("10:10", run(source));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                trap fnc value(int x): int { return x; }
                fnc bad(): void {
                  val Fnc<int, int> callback = value;
                  return;
                }
                """));
    }

    @Test
    void qualifiedModuleCallsRemainOptionTyped() throws Exception {
        String source = """
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                pub fnc main(): void {
                  val Option<int> wrapped = safely.value();
                  stdio.stdout.write(wrapped.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("23", run(source));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define module safely as
                  pub trap fnc value(): int { return 23; }
                end
                fnc bad(): void {
                  val int raw = safely.value();
                  return;
                }
                """));
    }

    @Test
    void returnedNoneIsSomeNoneAndRuntimeFailureIsOuterNone() throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                trap fnc optional(bool enabled): Option<int> {
                  if enabled; then
                    return Some(21);
                  fi
                  return None;
                }
                trap fnc failed(Animal animal): Option<int> {
                  val Dog dog = animal as Dog;
                  return None;
                }

                pub fnc main(): void {
                  val Option<Option<int>> none_value = optional(false);
                  val Option<Option<int>> some_value = optional(true);
                  val Option<Option<int>> failed_value = failed(new Cat());
                  stdio.stdout.write(none_value.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(none_value.unwrap().is_none());
                  stdio.stdout.write(":");
                  stdio.stdout.write(some_value.unwrap().unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(failed_value.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:true:21:true", run(source));
    }

    @Test
    void voidTrapReturnsSomeEvenWithoutValue() throws Exception {
        String source = """
                trap fnc done(): void {
                  return;
                }
                pub fnc main(): void {
                  val Option<void> completed = done();
                  stdio.stdout.write(completed.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(completed.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:false", run(source));
    }

    @Test
    void aTrapMayCallAnotherTrapWithoutImplicitFlattening() throws Exception {
        String source = """
                trap fnc inner(): int { return 11; }
                trap fnc outer(): Option<int> {
                  return inner();
                }
                pub fnc main(): void {
                  val Option<Option<int>> nested = outer();
                  stdio.stdout.write(nested.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(nested.unwrap().unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:11", run(source));
    }

    @Test
    void ordinaryFunctionsRemainUnwrapped() throws Exception {
        String source = """
                fnc raw(): int { return 3; }
                trap fnc safe(): int { return 7; }
                pub fnc main(): void {
                  val int a = raw();
                  val Option<int> b = safe();
                  stdio.stdout.write(a);
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("3:7", run(source));
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

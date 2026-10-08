package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class CallableSemanticsTest {

    @Test
    void namespaceModulesMainFncRoutineAndLambdaCompose() throws Exception {
        String output = run("""
                namespace demo;

                define module math
                  pub fnc factorial(int n): int {
                    return n == 0 ? 1 : n * factorial(n - 1);
                  }

                  pub fnc offset(int x): int {
                    return x + 10;
                  }
                end

                pub routine main(): void {
                  val int base = 7;
                  val Fnc<int, int> lexical = |int x| -> {
                    return x + base;
                  };
                  val Fnc<int, int> isolated = nlex |int x| -> {
                    val int base = 1;
                    return math.offset(x) + base;
                  };
                  stdio.stdout.write(math.factorial(5));
                  stdio.stdout.write(":");
                  stdio.stdout.write(lexical(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(isolated(2));
                  return;
                }
                """);

        assertEquals("120:9:13", output);
    }

    @Test
    void explicitNlexLambdaCannotCaptureOuterLocal() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc make(): (() => int) {
                          val int local = 42;
                          return nlex || -> {
                            return local;
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("local") || error.getMessage().contains("unknown name"));
    }

    @Test
    void nlexNamedCallableMakesNestedLambdasNonCapturing() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        nlex fnc make(): (() => int) {
                          val int local = 42;
                          return || -> {
                            return local;
                          };
                        }
                        """)));
        assertTrue(error.getMessage().contains("local") || error.getMessage().contains("unknown name"));
    }

    @Test
    void nlexStillResolvesModulesGlobalsAndOwnShadowingLocals() throws Exception {
        String output = run("""
                define module math
                  pub fnc one(): int { return 1; }
                end

                pub routine main(): void {
                  val int value = 99;
                  val Fnc<int> callback = nlex || -> {
                    val int value = 2;
                    return math.one() + value;
                  };
                  stdio.stdout.write(callback());
                  stdio.stdout.write(value);
                  return;
                }
                """);
        assertEquals("399", output);
    }

    @Test
    void contextualLambdaTypesSupportTheDemoProgram() throws Exception {
        String output = run("""
                namespace nlex_demo;

                type IntFn = typeof fnc(int value) => int;

                define module math
                  pub fnc factorial(int n): int {
                    return n == 0 ? 1 : n * factorial(n - 1);
                  }

                  pub fnc offset(int value): int {
                    return value + 10;
                  }
                end

                nlex fnc makeOffsetter(): IntFn {
                  return |value| -> {
                    return math.offset(value);
                  };
                }

                pub routine main(): void {
                  val int outer_bias = 100;

                  val IntFn lexical = |value| -> {
                    return value + outer_bias;
                  };

                  val IntFn explicit_nlex = nlex |value| -> {
                    val int outer_bias = 1;
                    return math.offset(value) + outer_bias;
                  };

                  val IntFn inherited_nlex = makeOffsetter();

                  stdio.stdout.write(math.factorial(5));
                  stdio.stdout.write(":");
                  stdio.stdout.write(lexical(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(explicit_nlex(2));
                  stdio.stdout.write(":");
                  stdio.stdout.write(inherited_nlex(5));
                  return;
                }
                """);

        assertEquals("120:102:13:15", output);
    }

    @Test
    void routineAndFncDifferByReifiabilityNotRecursion() throws Exception {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse("""
                        routine loop(bool finished): void {
                          if finished; do
                            return;
                          else
                            loop(true);
                            return;
                          fi
                        }
                        """)));

        IllegalArgumentException routineValue = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        routine work(int value): int {
                          return value + 1;
                        }

                        fnc bad(): void {
                          val Fnc<int, int> callback = work;
                        }
                        """)));
        assertTrue(routineValue.getMessage().contains("direct-call-only"));

        String output = run("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }

                fnc increment(int value): int {
                  return value + 1;
                }

                routine countdown(int value): int {
                  if value == 0; do
                    return 0;
                  else
                    return countdown(value - 1);
                  fi
                }

                pub routine main(): void {
                  val Fnc<int, int> callback = increment;
                  stdio.stdout.write(apply(callback, 4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(countdown(4));
                  return;
                }
                """);
        assertEquals("5:0", output);
    }

    @Test
    void boundInstanceMethodsStaticFncsAndExplicitLambdasAreFirstClass() throws Exception {
        assertDoesNotThrow(
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub addOne(int value): int {
                            return value + 1;
                          }
                        end

                        fnc bad(): void {
                          val box = new Box();
                          val Fnc<int, int> callback = box.addOne;
                        }
                        """)));

        String output = run("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }

                define class Box as
                  pub addOne(int value): int {
                    return value + 1;
                  }

                  pub static fnc twice(int value): int {
                    return value * 2;
                  }
                end

                pub routine main(): void {
                  val Fnc<int, int> wrapped = |int value| -> {
                    val box = new Box();
                    return box.addOne(value);
                  };
                  val Fnc<int, int> static_callback = Box.twice;
                  val box = new Box();
                  val Fnc<int, int> bound = box.addOne;

                  stdio.stdout.write(apply(wrapped, 4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(apply(static_callback, 4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(apply(bound, 9));
                  return;
                }
                """);
        assertEquals("5:8:10", output);
    }

    @Test
    void moduleAliasesKeepFncsFirstClassAndRoutinesDirectOnlyAtRuntime() throws Exception {
        String output = run("""
                define module service
                  pub fnc transform(int value): int {
                    return value + 1;
                  }

                  pub routine direct_only(int value): int {
                    return value + 2;
                  }
                end

                pub routine main(): void {
                  val alias = service;
                  val Fnc<int, int> callback = alias.transform;
                  stdio.stdout.write(callback(4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(alias.direct_only(4));
                  return;
                }
                """);

        assertEquals("5:6", output);
    }

    @Test
    void functionValuedFieldsRemainFirstClassWithoutBecomingMethods() throws Exception {
        String output = run("""
                fnc increment(int value): int {
                  return value + 1;
                }

                define class Box as
                  pub val Fnc<int, int> callback;
                end

                pub routine main(): void {
                  val box = new Box(increment);
                  stdio.stdout.write(box.callback(4));
                  stdio.stdout.write(":");
                  val Fnc<int, int> callback = box.callback;
                  stdio.stdout.write(callback(9));
                  return;
                }
                """);

        assertEquals("5:10", output);
    }

    @Test
    void localCallableBindingShadowsTopLevelDeclarationForDirectCalls() throws Exception {
        String output = run("""
                fnc value(): int {
                  return 1;
                }

                fnc invoke(Fnc<int> value): int {
                  return value();
                }

                pub routine main(): void {
                  val Fnc<int> local = || -> {
                    return 7;
                  };
                  stdio.stdout.write(invoke(local));
                  return;
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void expressionBodyLambdasSupportSingleLineMultilineAndLexicalCapture() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val int bias = 1;
                  val Fnc<int, int> twice = |value| -> value * 2;
                  val Fnc<int, int> shifted = |value| ->
                    value * 2 + bias;

                  stdio.stdout.write(twice(4));
                  stdio.stdout.write(":");
                  stdio.stdout.write(shifted(4));
                  return;
                }
                """);

        assertEquals("8:9", output);
    }

    @Test
    void blockLambdaNeverImplicitlyReturnsItsLastExpression() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val Fnc<int, int> bad = |value| -> {
                            value * 2;
                          };
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("explicitly return"));
    }

    @Test
    void expressionBodyLambdaRequiresExplicitStatementSemicolon() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub routine main(): void {
                          val Fnc<int, int> twice = |value| -> value * 2
                          stdio.stdout.write(twice(4));
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("expression-bodied lambda"));
        assertTrue(error.getMessage().contains("explicit ';'"));
    }

    @Test
    void expressionBodyLambdaChecksItsContextualReturnType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val Fnc<int, int> bad = |value| -> "not-an-int";
                          return;
                        }
                        """)));
    }

    @Test
    void onlyImmediateBraceAfterArrowStartsLambdaBlock() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val make = |value| -> infer struct{return: value};
                  return;
                }
                """));
    }

    @Test
    void nlexExpressionBodyCannotCaptureOuterActivation() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val int outer = 7;
                          val Fnc<int, int> bad = nlex |value| -> value + outer;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("outer") || error.getMessage().contains("unknown name"));
    }

    @Test
    void expressionBodyLambdaComposesAsCallArgumentReturnAndNestedClosure() throws Exception {
        String output = run("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }

                fnc makeAdder(int base): (() => int) {
                  return || -> base + 1;
                }

                pub routine main(): void {
                  val int direct = apply(|value| -> value * 2, 5);
                  val (() => (() => int)) outer = || -> || -> 9;
                  val inner = outer();
                  val add = makeAdder(4);

                  stdio.stdout.write(direct);
                  stdio.stdout.write(":");
                  stdio.stdout.write(inner());
                  stdio.stdout.write(":");
                  stdio.stdout.write(add());
                  return;
                }
                """);

        assertEquals("10:9:5", output);
    }

    @Test
    void typedLegacyExpressionLambdaAlsoUsesExpressionBodySemantics() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val Fnc<int, int> twice = (int value) -> value * 2;
                  stdio.stdout.write(twice(6));
                  return;
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void expressionBodyLambdaInReturnStillRequiresExplicitSemicolon() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc make(): (() => int) {
                          return || -> 7
                        }
                        """));

        assertTrue(error.getMessage().contains("expression-bodied lambda"));
        assertTrue(error.getMessage().contains("explicit ';'"));
    }

    @Test
    void expressionBodyLambdaCanReturnStructuredObjectWithoutBraceConfusion() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val Fnc<int, {value: int}> wrap =
                    |value| -> struct{value: int}{value: value * 2};
                  val wrapped = wrap(4);
                  stdio.stdout.write(wrapped.value);
                  return;
                }
                """);

        assertEquals("8", output);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callables.ores")
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

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TupleSyntaxTest {
    @Test
    void explicitAndParenthesizedTupleReturnTypesInteroperate() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc explicit(): Tuple[string, int] {
                  return ("foo", 5);
                }

                fnc shorthand(): (string, int) {
                  return explicit();
                }

                fnc explicitAgain(): Tuple[string, int] {
                  return shorthand();
                }

                pub fnc main(): void {
                  val t = explicitAgain();
                  stdio.println(t[0]);
                  stdio.println(t[1]);
                  return;
                }
                """)));
    }

    @Test
    void parenthesizedDestructureSupportsInferenceAndPerSlotTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc foo(): Tuple[string, int] {
                  return ("foo", 5);
                }

                pub fnc main(): void {
                  const (k, v) = foo();
                  const (string k2, int v2) = foo();
                  const (k3: string, v3: int) = foo();
                  stdio.println(k);
                  stdio.println(v);
                  stdio.println(k2);
                  stdio.println(v2);
                  stdio.println(k3);
                  stdio.println(v3);
                  return;
                }
                """)));
    }

    @Test
    void typedTupleDestructureIsPositionalAndRejectsSwappedTypes() {
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc foo(): Tuple[string, int] {
                          return ("foo", 5);
                        }

                        pub fnc main(): void {
                          const (int k, string v) = foo();
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("destructure binding"));
    }

    @Test
    void countedOfGroupingIsEquivalentInsideTupleShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc grouped(): Tuple[(1 of string, 1 of int)] {
                  return ("foo", 5);
                }

                fnc flat(): Tuple[1 of string, 1 of int] {
                  return grouped();
                }

                fnc roundTrip(): Tuple[(1 of string, 1 of int)] {
                  return flat();
                }

                pub fnc main(): void {
                  val x = roundTrip();
                  stdio.println(x[0]);
                  stdio.println(x[1]);
                  return;
                }
                """)));
    }

    @Test
    void parenthesizedTupleElementInsideTupleShapeRemainsNested() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc nested(): Tuple[(string, int), bool] {
                  return (("foo", 5), true);
                }

                pub fnc main(): void {
                  val n: int = nested()[0][1];
                  val b: bool = nested()[1];
                  stdio.println(n);
                  stdio.println(b);
                  return;
                }
                """)));
    }

    @Test
    void explicitTupleConstructorCanInferOrUseAnExplicitShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc inferred(): Tuple[string, int] {
                  return new Tuple("foo", 5);
                }

                fnc explicit(): (string, int) {
                  return new Tuple[string, int]("bar", 6);
                }

                fnc consume(Tuple[string, int] t): int {
                  return t[1];
                }

                pub fnc main(): void {
                  val a: int = consume(("inline", 7));
                  val b: int = consume(new Tuple("explicit", 8));
                  stdio.println(a);
                  stdio.println(b);
                  return;
                }
                """)));
    }

    @Test
    void tupleConstantIndexingPreservesNestedSlotTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc nested(): Tuple[Tuple[string, int], Tuple[bool, string]] {
                  return (("foo", 5), (true, "ok"));
                }

                pub fnc main(): void {
                  val n: int = nested()[0][1];
                  val s: string = nested()[1][1];
                  stdio.println(n);
                  stdio.println(s);
                  return;
                }
                """)));

        IllegalArgumentException bounds = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc pair(): Tuple[string, int] {
                          return ("foo", 5);
                        }

                        pub fnc main(): void {
                          val x = pair()[2];
                          return;
                        }
                        """)));
        assertTrue(bounds.getMessage().contains("out of bounds"));
    }

    @Test
    void tupleBracketPseudoConstructionAndEmptyConstructorAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc main(): void {
                  val bad = Tuple[][5, "foo"];
                  return;
                }
                """));

        IllegalArgumentException empty = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val bad = new Tuple();
                          return;
                        }
                        """)));
        assertTrue(empty.getMessage().contains("at least one element"));
    }

    @Test
    void tupleConstructionDestructureAndNestedIndexingExecute() throws Exception {
        String program = """
                fnc foo(): Tuple[string, int] {
                  return new Tuple("foo", 5);
                }

                fnc nested(): (Tuple[string, int], Tuple[bool, string]) {
                  return (foo(), new Tuple(true, "ok"));
                }

                pub fnc main(): void {
                  const (string k, int v) = foo();
                  val n: int = nested()[0][1];
                  val s: string = nested()[1][1];
                  stdio.println(k);
                  stdio.println(v);
                  stdio.println(n);
                  stdio.println(s);
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tuple-syntax.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("foo"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("ok"));
    }
}

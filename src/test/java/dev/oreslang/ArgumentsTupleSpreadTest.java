package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ArgumentsTupleSpreadTest {

    @Test
    void acceptsNameFirstCompatibilityParametersButKeepsTypeFirstCanonicalSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc canonical(int value): int {
                  return value;
                }

                fnc compatibility(value: int): int {
                  return canonical(value);
                }
                """)));
    }

    @Test
    void argumentsIsATypedFixedTupleAndCanForwardIntoFixedArityFunction() throws Exception {
        String program = """
                fnc add(int left, int right): int {
                  return left + right;
                }

                fnc forward(left: int, right: int): int {
                  const args = arguments;
                  return add(...args);
                }

                pub routine main(): void {
                  stdio.stdout.write(forward(20, 22));
                  return;
                }
                """;

        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("42", run(program));
    }

    @Test
    void directArgumentsSpreadUsesDeclarationOrder() throws Exception {
        String program = """
                fnc join(String first, String second): String {
                  return first + second;
                }

                fnc forward(String first, String second): String {
                  return join(...arguments);
                }

                pub routine main(): void {
                  stdio.stdout.write(forward("ore", "slang"));
                  return;
                }
                """;

        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("oreslang", run(program));
    }

    @Test
    void literalFixedTupleSpreadIsStaticallyExpanded() throws Exception {
        String program = """
                fnc add(int left, int right): int {
                  return left + right;
                }

                pub routine main(): void {
                  stdio.stdout.write(add(...(19, 23)));
                  return;
                }
                """;

        var typed = TypeChecker.check(Parser.parse(program));
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
        assertEquals("42", run(program));
    }

    @Test
    void runtimeLengthCollectionsCannotSpreadIntoFixedArityCallables() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc add(int left, int right): int {
                          return left + right;
                        }

                        fnc bad(List<int> values): int {
                          return add(...values);
                        }
                        """)));

        assertTrue(error.getMessage().contains("statically known tuple"), error.getMessage());
    }

    @Test
    void fixedTupleSpreadStillChecksExpandedArity() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc one(int value): int {
                          return value;
                        }

                        fnc bad(int left, int right): int {
                          return one(...arguments);
                        }
                        """)));

        assertTrue(error.getMessage().contains("arity"), error.getMessage());
    }

    @Test
    void consumingMoveOnlyArgumentsTupleMovesItsUnderlyingParameters() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Box as
                        end

                        fnc consume(Box box): int {
                          return 1;
                        }

                        fnc bad(Box box): int {
                          const args = arguments;
                          const first = consume(...args);
                          return consume(box);
                        }
                        """)));

        assertTrue(error.getMessage().contains("moved"), error.getMessage());
        assertTrue(error.getMessage().contains("box"), error.getMessage());
    }

    @Test
    void argumentsCannotBeShadowedByAParameter() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(int arguments): int {
                          return arguments;
                        }
                        """)));

        assertTrue(error.getMessage().contains("arguments"), error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "arguments-tuple-spread.ores")
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

package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class BooleanIntrinsicsTest {
    @Test
    void builtinGlobalsNeedNoImportsAndSurviveBuildAdmission() throws Exception {
        String program = """
                pub fnc main(): void {
                  stdio.println(Some(And(true, true)).unwrap());
                  stdio.println(Ok(Or(false, true)).unwrap());
                  stdio.println(Xor(false, true, false));
                  stdio.println(None.is_none());
                  return;
                }
                """;
        var parsed = TypeChecker.check(Parser.parse(program));
        assertTrue(parsed.imports().isEmpty());
        var built = OresCompiler.compileForBuild(program, BuildOptions.executable(Map.of()));
        assertTrue(built.program().imports().isEmpty());
        assertDoesNotThrow(() -> TypeChecker.check(built.program()));
        assertEquals("true\ntrue\ntrue\ntrue\n", run(program, "boolean-builtin-globals.ores"));
    }

    @Test
    void exhaustiveTruthTablesForGlobalAndQualifiedScalarAndArrayForms() throws Exception {
        StringBuilder program = new StringBuilder("pub fnc main(): void {\n");
        StringBuilder expected = new StringBuilder();
        for (int size = 0; size <= 6; size++) {
            for (int mask = 0; mask < (1 << size); mask++) {
                StringJoiner operands = new StringJoiner(", ");
                for (int bit = 0; bit < size; bit++) {
                    operands.add(Boolean.toString((mask & (1 << bit)) != 0));
                }
                for (String name : new String[]{"And", "Or", "Xor"}) {
                    boolean result = switch (name) {
                        case "And" -> mask == (1 << size) - 1;
                        case "Or" -> mask != 0;
                        case "Xor" -> Integer.bitCount(mask) % 2 == 1;
                        default -> throw new AssertionError(name);
                    };
                    for (String prefix : new String[]{"", "BooleanOps."}) {
                        program.append("stdio.println(").append(prefix).append(name)
                                .append("([").append(operands).append("]));\n");
                        expected.append(result).append('\n');
                        if (size >= 2) {
                            program.append("stdio.println(").append(prefix).append(name)
                                    .append('(').append(operands).append("));\n");
                            expected.append(result).append('\n');
                        }
                    }
                }
            }
        }
        program.append("return;\n}");
        assertEquals(expected.toString(), run(program.toString(), "boolean-truth-tables.ores"));
    }

    @Test
    void typedArrayVariablesWorkForEveryIntrinsic() throws Exception {
        assertEquals("false\ntrue\nfalse\nfalse\ntrue\nfalse\n", run("""
                pub fnc main(): void {
                  val Array<bool> values = [true, false, true];
                  stdio.println(And(values));
                  stdio.println(Or(values));
                  stdio.println(Xor(values));
                  stdio.println(BooleanOps.And(values));
                  stdio.println(BooleanOps.Or(values));
                  stdio.println(BooleanOps.Xor(values));
                  return;
                }
                """, "boolean-array-variables.ores"));
    }

    @Test
    void scalarShortCircuitingSkipsOnlyOperandsAfterTheDecisiveValue() throws Exception {
        assertEquals("false\ntrue\nfalse\ntrue\n", run("""
                pub fnc main(): void {
                  stdio.println(And(true, false, [true][99]));
                  stdio.println(Or(false, true, [false][99]));
                  stdio.println(BooleanOps.And(true, false, [true][99]));
                  stdio.println(BooleanOps.Or(false, true, [false][99]));
                  return;
                }
                """, "boolean-short-circuit.ores"));

        for (String prefix : new String[]{"", "BooleanOps."}) {
            for (String expression : new String[]{
                    "And([true][99], false)", "Or([false][99], true)",
                    "Xor(false, false, [true][99])", "Xor(true, false, [true][99])",
                    "And([false, [true][99]])", "Or([true, [false][99]])"}) {
                PolyglotException failure = assertThrows(PolyglotException.class,
                        () -> run("pub fnc main(): void { stdio.println(" + prefix + expression
                                + "); return; }", "boolean-evaluation-order.ores"), prefix + expression);
                assertTrue(failure.getMessage().contains("99"), failure.getMessage());
            }
        }
    }

    @Test
    void everyIntrinsicRejectsEmptyCallsScalarSingletonsAndNonBooleanOperands() {
        for (String prefix : new String[]{"", "BooleanOps."}) {
            for (String name : new String[]{"And", "Or", "Xor"}) {
                for (String operands : new String[]{"", "true", "1", "[1]", "[true, 1]",
                        "true, 1", "true, false, 1", "[true], [false]"}) {
                    String call = prefix + name + "(" + operands + ")";
                    assertThrows(IllegalArgumentException.class,
                            () -> TypeChecker.check(Parser.parse(
                                    "fnc bad(): bool { return " + call + "; }")), call);
                }
            }
        }
    }

    @Test
    void shadowedFunctionsKeepTheirDeclaredArrayOwnership() {
        for (String name : new String[]{"And", "Or", "Xor"}) {
            for (boolean qualified : new boolean[]{false, true}) {
                String declaration = "pub fnc " + name + "(Array<bool> values): bool { return true; }";
                if (qualified) declaration = "define module BooleanOps as\n" + declaration + "\nend\n";
                String program = declaration + """
                        pub fnc main(): void {
                          val Array<bool> values = [true, false];
                        """ + (qualified ? "BooleanOps." : "") + name + "(values);\n"
                        + "stdio.println(values[0]); return; }";
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(program)));
                assertTrue(failure.getMessage().contains("moved value 'values'"), failure.getMessage());
            }
        }
    }

    @Test
    void intrinsicArraysCanBeReadRepeatedlyInsideLoops() throws Exception {
        assertEquals("true\ntrue\nfalse\ntrue\ntrue\nfalse\n", run("""
                pub fnc main(): void {
                  val Array<bool> values = [true, true];
                  for const i of [0, 1] {
                    stdio.println(And(values));
                    stdio.println(BooleanOps.Or(values));
                    stdio.println(Xor(values));
                  }
                  return;
                }
                """, "boolean-array-loop.ores"));
    }

    @Test
    void typeChecksAritySelectedScalarAndListOverloads() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc combine(bool foo, bool bar, bool x, bool y): bool {
                  return Or(foo, bar, And(x, y));
                }

                fnc from_array(Array<bool> values): bool {
                  return BooleanOps.And(values);
                }

                fnc qualified(bool a, bool b, bool c): bool {
                  return BooleanOps.Or(a, b, c);
                }

                fnc empty_xor(): bool {
                  return Xor([]);
                }
                """)));
    }

    @Test
    void rejectsInvalidArityAndOperandTypes() {
        IllegalArgumentException scalarAtArityOne = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(bool value): bool {
                          return And(value);
                        }
                        """)));
        assertTrue(scalarAtArityOne.getMessage().contains("arity 1"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Or();
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Xor(true, 1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return BooleanOps.And([true, 1]);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  return Or<bool>(true, false);
                }
                """)));
    }

    @Test
    void moduleAndTopLevelFunctionsOverloadByNameAndExactArity() throws Exception {
        String program = """
                define module Picks as
                  pub fnc pick(int x): int {
                    return x;
                  }

                  pub fnc pick(int x, int y): int {
                    return x + y;
                  }
                end

                fnc local(int x): int {
                  return x + 10;
                }

                fnc local(int x, int y): int {
                  return x + y + 10;
                }

                pub fnc main(): void {
                  stdio.println(Picks.pick(2));
                  stdio.println(Picks.pick(2, 3));
                  stdio.println(local(1));
                  stdio.println(local(1, 2));
                  return;
                }
                """;

        assertEquals("2\n5\n11\n13\n", run(program, "module-overloads.ores"));

        IllegalArgumentException duplicateArity = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module Bad as
                          pub fnc choose(int value): int {
                            return value;
                          }

                          pub fnc choose(bool value): int {
                            return 0;
                          }
                        end
                        """)));
        assertTrue(duplicateArity.getMessage().contains("name + arity"));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module AmbiguousValue as
                  pub fnc choose(int value): int {
                    return value;
                  }

                  pub fnc choose(int left, int right): int {
                    return left + right;
                  }
                end

                fnc bad(): void {
                  val callback = AmbiguousValue.choose;
                  return;
                }
                """)));
    }

    @Test
    void realBooleanOpsModuleShadowsBuiltinNamespace() throws Exception {
        String program = """
                define module BooleanOps as
                  pub fnc And(int left, int right): int {
                    return left + right;
                  }
                end

                pub fnc main(): void {
                  stdio.println(BooleanOps.And(2, 3));
                  return;
                }
                """;

        assertEquals("5\n", run(program, "boolean-ops-shadow.ores"));
    }

    @Test
    void runtimeUsesLogicalIdentitiesParityAndScalarShortCircuiting() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Array<bool> values = [false, false, true];

                  stdio.println(And(true, true, false));
                  stdio.println(BooleanOps.Or(false, false, true));
                  stdio.println(Xor(true, false, true));

                  stdio.println(BooleanOps.And([]));
                  stdio.println(Or([]));
                  stdio.println(Xor([]));

                  stdio.println(And(false, [true][99]));
                  stdio.println(BooleanOps.Or(true, [false][99]));

                  stdio.println(And([true, true, false]));
                  stdio.println(BooleanOps.Or(values));
                  stdio.println(Xor([true, true, false, true]));
                  return;
                }
                """;

        assertEquals(
                """
                false
                true
                false
                true
                false
                false
                false
                true
                false
                true
                true
                """,
                run(program, "boolean-intrinsics.ores"));
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, name)
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

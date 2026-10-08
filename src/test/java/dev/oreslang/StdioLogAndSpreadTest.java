package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StdioLogAndSpreadTest {
    @Test
    void stdoutLogConcatenatesArgumentsAndAppendsOneNewline() throws Exception {
        String output = run("""
                pub routine main(): void {
                  stdio.stdout.log("ORES_TEST|", "PASS", "|", "suite/case", "|", "ok");
                  stdio.stdout.log();
                  return;
                }
                """);

        assertEquals("ORES_TEST|PASS|suite/case|ok\n\n", output);
    }

    @Test
    void stdoutLogSpreadAndLogListHaveIdenticalSequenceSemantics() throws Exception {
        String output = run("""
                pub routine main(): void {
                  val parts = arr["A", 1, "B", true];
                  stdio.stdout.log(...parts);
                  stdio.stdout.logList(parts);
                  stdio.stdout.logList(("X", 2, "Y"));
                  return;
                }
                """);

        assertEquals("A1Btrue\nA1Btrue\nX2Y\n", output);
    }

    @Test
    void stdoutSpreadRequiresAnArrayListOrTuple() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          stdio.stdout.log(...42);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("requires an array/list/tuple value"));
    }

    @Test
    void dynamicSpreadDoesNotWeakenFixedArityCallChecking() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc pair(int left, int right): void {
                          return;
                        }

                        pub routine main(): void {
                          val values = arr[1, 2];
                          pair(...values);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("spread arguments require a variadic callable"));
    }

    @Test
    void treeShakerPreservesSpreadOperands() {
        assertDoesNotThrow(() -> OresCompiler.compileForBuild("""
                pub routine main(): void {
                  val parts = arr["A", "B"];
                  stdio.stdout.log(...parts);
                  return;
                }
                """, BuildOptions.executable(java.util.Map.of())));
    }

    @Test
    void capabilityCheckerTraversesSpreadOperands() {
        var program = TypeChecker.check(Parser.parse("""
                pub routine main(): void {
                  val values = arr[process.descriptor];
                  stdio.stdout.log(...values);
                  return;
                }
                """));

        SecurityException failure = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));

        assertTrue(failure.getMessage().contains("PROCESS_INFO"));
    }

    @Test
    void spreadSyntaxIsRestrictedToCallArgumentLists() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub routine main(): void {
                          val values = arr[1, 2];
                          val invalid = ...values;
                          return;
                        }
                        """));
    }

    @Test
    void mixedSpreadArgumentsPreserveLeftToRightFlattening() throws Exception {
        String output = run("""
                pub routine main(): void {
                  stdio.stdout.log("A", ...arr[1, 2], "B", ...("C", 3));
                  return;
                }
                """);

        assertEquals("A12BC3\n", output);
    }

    @Test
    void logListRejectsScalarValues() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          stdio.stdout.logList(42);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("requires an array/list/tuple value"));
    }

    @Test
    void variadicLogCannotBeReifiedWithoutAnExplicitLambda() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        pub routine main(): void {
                          val logger = stdio.stdout.log;
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("direct-call-only"));
    }

    @Test
    void shadowedStdioDoesNotReceiveBuiltinVariadicPrivileges() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Sink as
                          pub log(int value): void {
                            return;
                          }
                        end

                        define class LocalStdio as
                          pub val Sink stdout;
                        end

                        fnc use(LocalStdio stdio): void {
                          val values = arr[1, 2];
                          stdio.stdout.log(...values);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("spread arguments require a variadic callable"));
    }

    @Test
    void shadowedStdioDoesNotReceiveBuiltinReadOnlyOwnershipSemantics() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                        define class Payload as
                        end

                        define class Sink as
                          pub log(Payload value): void {
                            return;
                          }
                        end

                        define class LocalStdio as
                          pub val Sink stdout;
                        end

                        fnc use(LocalStdio stdio, Payload payload): void {
                          stdio.stdout.log(payload);
                          stdio.stdout.log(payload);
                          return;
                        }
                        """)));

        assertTrue(failure.getMessage().contains("moved"));
    }

    @Test
    void staticClassGenericReferencesCannotHideInsideSpreadOperands() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Bad<T> as
                          pub static fnc leakInside(): void {
                            stdio.stdout.log(...arr[process.dynamic as T]);
                            return;
                          }
                        end
                        """)));

        assertTrue(failure.getMessage().contains("cannot reference enclosing class generic"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "stdio-log-spread.ores")
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

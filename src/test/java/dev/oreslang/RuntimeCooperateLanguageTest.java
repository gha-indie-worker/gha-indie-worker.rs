package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class RuntimeCooperateLanguageTest {
    @TempDir Path temp;

    @Test
    void cooperateAndLegacyYieldAliasResumeStacklessly() throws Exception {
        assertEquals("42", run("""
                define module app as
                  fnc bounce(): int {
                    rt cooperate;
                    rt cooperate();
                    rt yield;
                    rt yield();
                    return 42;
                  }

                  pub async fnc main(): void {
                    stdio.println(bounce());
                    return;
                  }
                end
                """));
    }

    @Test
    void parserCanonicalizesBothSpellingsToCooperateAst() {
        for (String spelling : new String[]{"rt cooperate;", "rt yield;"}) {
            Ast.Program program = Parser.parse("""
                    fnc handoff(): void {
                      """ + spelling + """
                      return;
                    }
                    """);
            Ast.FunctionDecl fn =
                    (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
            Ast.ExprStmt statement = (Ast.ExprStmt) fn.body().getFirst();
            Ast.RuntimeCallExpr runtime = assertInstanceOf(
                    Ast.RuntimeCallExpr.class,
                    statement.expression());
            assertEquals("cooperate", runtime.operation());
            assertTrue(runtime.arguments().isEmpty());
        }

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  val rt = 1;
                  return;
                }
                """));
    }

    @Test
    void cooperateArgumentsFailClosedUntilSchedulingHintsLand() {
        for (String spelling : new String[]{"rt cooperate(1);", "rt yield(1);"}) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("""
                            fnc bad(): void {
                              """ + spelling + """
                              return;
                            }
                            """));
            assertTrue(failure.getMessage().contains("rt cooperate currently takes no arguments"),
                    failure.getMessage());
        }
    }

    @Test
    void untrustedActorCannotLaunderCooperateThroughHelper() {
        var program = TypeChecker.check(Parser.parse("""
                define module app as
                  fnc cooperativeHelper(): void {
                    rt cooperate;
                    return;
                  }

                  pub untrusted actor fnc worker(): void {
                    cooperativeHelper();
                    return;
                  }
                end
                """));

        SecurityException denied = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(denied.getMessage().contains("rt cooperate"), denied.getMessage());
    }

    @Test
    void cooperateTypeChecksAsVoidSchedulingEffect() {
        assertEquals(1, TypeChecker.check(Parser.parse("""
                fnc handoff(): void {
                  rt cooperate;
                  return;
                }
                """)).modules().size());
    }

    private String run(String program) throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}

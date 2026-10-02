package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ClosureCaptureAnalyzer;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LexicalPrivacyTest {

    @Test
    void lexIsTheSingleExplicitKeywordAndLexicalRemainsAnOrdinaryIdentifier() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val int base = 10;
                  val int lexical = 5;

                  val Fnc<int, int> explicit = lex |int x| -> {
                    return x + base + lexical;
                  };

                  stdio.stdout.write(explicit(2));
                  return;
                }
                """);

        assertEquals("17", output);
    }

    @Test
    void namedLexModifierIsPreservedForTooling() {
        Ast.Program program = Parser.parse("""
                lex fnc helper() => int {
                  return 1;
                }

                lex routine task() => void {
                  return;
                }
                """);

        Ast.FunctionDecl helper = null;
        Ast.FunctionDecl task = null;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    if (function.name().equals("helper")) helper = function;
                    if (function.name().equals("task")) task = function;
                }
            }
        }

        assertNotNull(helper);
        assertNotNull(task);
        assertTrue(helper.explicitLexical());
        assertTrue(task.explicitLexical());
        assertFalse(helper.nonLexical());
        assertFalse(task.nonLexical());
    }

    @Test
    void immediateLexicalLambdaCallUsesNormalLexicalSemantics() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val int base = 40;
                  stdio.stdout.write((lex |int x| -> {
                    return base + x;
                  })(2));
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void captureKeywordsRemainUsableAsMemberNames() throws Exception {
        String output = run("""
                pub routine main() => void {
                  val settings = obj {
                    "lex": 1,
                    "lexical": 2,
                    "nlex": 3
                  };

                  stdio.stdout.write(settings.lex);
                  stdio.stdout.write(settings.lexical);
                  stdio.stdout.write(settings.nlex);
                  return;
                }
                """);

        assertEquals("123", output);
    }

    @Test
    void lexCannotPunchThroughInheritedNlexBarrier() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        nlex fnc make() => (() -> int) {
                          val int local = 42;
                          return lex || -> {
                            return local;
                          };
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot override an enclosing nlex capture barrier"));
    }

    @Test
    void lexicalCapturePlanOmitsUnreferencedSecret() {
        Ast.LambdaExpr lambda = returnedLambda("""
                fnc make() => (() -> int) {
                  val int secret = 99;
                  val int visible = 7;
                  return lex || -> {
                    return visible;
                  };
                }
                """, "make");

        ClosureCaptureAnalyzer.CapturePlan plan = ClosureCaptureAnalyzer.analyze(lambda);
        assertEquals(List.of("visible"), plan.names());
        assertFalse(plan.names().contains("secret"));
        assertTrue(plan.requiresEnvironment());
    }

    @Test
    void captureFreeLexLambdaUsesNoEnvironmentPlan() {
        Ast.LambdaExpr lambda = returnedLambda("""
                fnc make() => (() -> int) {
                  return lex || -> {
                    return 7;
                  };
                }
                """, "make");

        ClosureCaptureAnalyzer.CapturePlan plan = ClosureCaptureAnalyzer.analyze(lambda);
        assertEquals(List.of(), plan.names());
        assertFalse(plan.requiresEnvironment());
    }

    @Test
    void outerClosureRetainsGrandparentLocalNeededByNestedLexicalLambda() {
        Ast.LambdaExpr lambda = returnedLambda("""
                fnc make() {
                  val int secret = 7;
                  return || -> {
                    return || -> {
                      return secret;
                    };
                  };
                }
                """, "make");

        ClosureCaptureAnalyzer.CapturePlan plan = ClosureCaptureAnalyzer.analyze(lambda);
        assertEquals(List.of("secret"), plan.names());
    }

    @Test
    void nestedNlexLambdaIsATransitiveCaptureBarrier() {
        Ast.LambdaExpr lambda = returnedLambda("""
                fnc make() {
                  val int secret = 7;
                  return || -> {
                    return nlex || -> {
                      return secret;
                    };
                  };
                }
                """, "make");

        ClosureCaptureAnalyzer.CapturePlan plan = ClosureCaptureAnalyzer.analyze(lambda);
        assertEquals(List.of(), plan.names());
    }

    @Test
    void transitiveNestedCaptureMovesMoveOnlyGrandparentValue() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box
                          pub val int value = 7;
                        end

                        fnc bad() => void {
                          let Box box = new Box();

                          val outer = || -> {
                            return || -> {
                              return box.value;
                            };
                          };

                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    @Test
    void duplicateAndMixedCaptureModifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                lex lex fnc bad() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                lex nlex fnc bad() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                nlex lex fnc bad() => void { return; }
                """));
    }

    private static Ast.LambdaExpr returnedLambda(String source, String functionName) {
        Ast.Program program = Parser.parse(source);
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function && function.name().equals(functionName)) {
                    for (Ast.Stmt statement : function.body()) {
                        if (statement instanceof Ast.ReturnStmt returned && returned.value() instanceof Ast.LambdaExpr lambda) {
                            return lambda;
                        }
                    }
                }
            }
        }
        throw new AssertionError("missing returned lambda in " + functionName);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "lexical-privacy.ores")
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

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class BooleanTypeAliasTest {
    @Test
    void canonicalizesOnlyTheUnqualifiedPrimitiveSpelling() {
        assertEquals(Ast.TypeRef.simple("bool"), Ast.TypeRef.simple("boolean"));
        assertEquals(new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("bool")), false),
                new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("boolean")), false));
        assertEquals("Example.boolean", Ast.TypeRef.simple("Example.boolean").name());
    }

    @Test
    void bothSpellingsWorkWithEveryScalarAndArrayIntrinsic() throws Exception {
        for (String spelling : new String[]{"bool", "boolean"}) {
            assertEquals("false\ntrue\nfalse\nfalse\ntrue\nfalse\nfalse\ntrue\nfalse\n", run("""
                    fnc combine(%s a, bool b, boolean c): Array<boolean> {
                      return [And(a, b, c), Or(a, b, c), Xor(a, b, c)];
                    }
                    pub fnc main(): void {
                      val Array<%s> values = [true, false, true];
                      stdio.println(And(values));
                      stdio.println(Or(values));
                      stdio.println(Xor(values));
                      stdio.println(BooleanOps.And(values));
                      stdio.println(BooleanOps.Or(values));
                      stdio.println(BooleanOps.Xor(values));
                      val results = combine(true, false, true);
                      stdio.println(results[0]);
                      stdio.println(results[1]);
                      stdio.println(results[2]);
                      return;
                    }
                    """.formatted(spelling, spelling)));
        }
    }

    @Test
    void aliasesPreserveCopySemanticsGenericCallsAndRuntimeTypeChecks() throws Exception {
        assertEquals("true\ntrue\ntrue\ntrue\nfalse\ntrue\n", run("""
                type Flag = boolean;
                fnc identity<T>(T value): T { return value; }
                fnc keep(boolean value): bool { return value; }
                fnc detect(bool value): boolean {
                  if value is type boolean flag then
                    return flag;
                  else
                    return false;
                  fi
                }
                fnc boxed(boolean value): Option<boolean> { return Some(value); }
                pub fnc main(): void {
                  val boolean flag = true;
                  val Flag same = flag;
                  stdio.println(keep(flag));
                  stdio.println(keep(flag));
                  stdio.println(identity<boolean>(same));
                  stdio.println(detect(flag));
                  stdio.println(false as boolean);
                  stdio.println(boxed(flag).unwrap());
                  return;
                }
                """));
    }

    @Test
    void callableSignaturesUseOneBooleanType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Predicate = typeof fnc(boolean) => bool;
                fnc keep(bool value): boolean { return value; }
                fnc apply(): boolean {
                  val Predicate predicate = keep;
                  return predicate(true);
                }
                """)));
    }

    @Test
    void buildDefinesRecognizeBooleanAnnotations() {
        var result = OresCompiler.compileForBuild("""
                pub const boolean enabled = false;
                pub fnc main(): void {
                  if enabled then
                    stdio.println("enabled");
                  else
                    stdio.println("disabled");
                  fi
                  return;
                }
                """, BuildOptions.executable(Map.of("enabled", "true")));
        Ast.FunctionDecl main = result.program().modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(function -> function.name().equals("main"))
                .findFirst().orElseThrow();
        Ast.BlockStmt selected = assertInstanceOf(Ast.BlockStmt.class, main.body().getFirst());
        Ast.ExprStmt statement = assertInstanceOf(Ast.ExprStmt.class, selected.body().getFirst());
        Ast.CallExpr call = assertInstanceOf(Ast.CallExpr.class, statement.expression());
        assertEquals("enabled", assertInstanceOf(Ast.LiteralExpr.class, call.arguments().getFirst()).value());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.compileForBuild("""
                pub const boolean enabled = false;
                pub fnc main(): void { stdio.println(enabled); return; }
                """, BuildOptions.executable(Map.of("enabled", "not-a-bool"))));
    }

    @Test
    void rejectsInvalidBooleanValuesAndTypeArguments() {
        for (String spelling : new String[]{"bool", "boolean"}) {
            for (String program : new String[]{
                    "fnc bad(): " + spelling + " { return 1; }",
                    "fnc bad(): void { val " + spelling + " value = 1; return; }",
                    "fnc bad(): " + spelling + " { return And([true, 1]); }",
                    "fnc bad(): " + spelling + " { return Or(true, 1); }",
                    "fnc bad(): " + spelling + " { return Xor(false, 1); }"}) {
                assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(program)), program);
            }
            for (String suffix : new String[]{"<int>", "<>", "[int]"}) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> TypeChecker.check(Parser.parse(
                                "fnc bad(" + spelling + suffix + " value): void { return; }")));
                assertTrue(failure.getMessage().contains("does not accept type arguments"), failure.getMessage());
            }
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                    "import type " + spelling + " from \"./flags\";")));
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "boolean-alias.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

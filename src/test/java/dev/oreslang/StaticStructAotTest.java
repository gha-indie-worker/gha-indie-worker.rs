package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StaticStructAotTest {

    @Test
    void reservedWordsRemainCallableNamesAndStaticStructKeys() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> check("""
                define module app
                  fnc stop(): int { return 1; }
                  routine do(): int { return 2; }
                  fnc done(): int { return 3; }

                  pub fnc main(): int {
                    const values = infer struct{stop: 4, 'do': 5, "done": 6};
                    return stop() + do() + done()
                        + values["stop"] + values["do"] + values["done"];
                  }
                end
                """));
    }

    @Test
    void inferredStructWhitespaceDoesNotChangeClosedShape() throws Exception {
        String output = run("""
                pub routine main(): void {
                  const left = infer struct{foo: "bar", answer: 42};
                  const right = infer struct {foo: "bar", answer: 42};
                  stdio.stdout.write(left.foo);
                  stdio.stdout.write(":");
                  stdio.stdout.write(right.answer);
                  return;
                }
                """);

        assertEquals("bar:42", output);
    }

    @Test
    void reversedInferredStructSpellingIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc bad(): void { const value = struct infer{foo: 1}; return; }"));
        assertTrue(error.getMessage().contains("infer struct"), error.getMessage());
    }

    @Test
    void objLiteralIsRemovedWithStructMigrationDiagnostic() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(): void {
                          const value = obj{foo: 1};
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("obj{...} has been removed"), error.getMessage());
        assertTrue(error.getMessage().contains("infer struct"), error.getMessage());
    }

    @Test
    void computedStructKeysAreRejectedForAotShape() {
        IllegalArgumentException first = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(string key): void {
                          const value = infer struct{`key`: 1};
                          return;
                        }
                        """));
        assertTrue(first.getMessage().contains("statically named"), first.getMessage());

        IllegalArgumentException second = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(string key): void {
                          const value = struct{foo: int}{`key`: 1};
                          return;
                        }
                        """));
        assertTrue(second.getMessage().contains("statically named")
                        || second.getMessage().contains("declared fields"),
                second.getMessage());
    }

    @Test
    void dynamicStructTypeIsRemovedEvenWithoutConstruction() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        fnc bad(DynamicStruct<int> value): void {
                          return;
                        }
                        """));

        assertTrue(error.getMessage().contains("DynamicStruct has been removed"), error.getMessage());
        assertTrue(error.getMessage().contains("AOT"), error.getMessage());
    }

    @Test
    void dynamicStructCannotHideBehindAliasesOrGenericWrappers() {
        IllegalArgumentException alias = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        type Legacy = DynamicStruct<int>;

                        fnc bad(Legacy value): void {
                          return;
                        }
                        """));
        assertTrue(alias.getMessage().contains("DynamicStruct has been removed"), alias.getMessage());

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        fnc bad(Option<DynamicStruct<int>> value): void {
                          return;
                        }
                        """));
        assertTrue(nested.getMessage().contains("DynamicStruct has been removed"), nested.getMessage());
    }

    @Test
    void duplicateStaticKeysAreRejectedAfterCanonicalization() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        fnc bad(): void {
                          const value = infer struct{foo: 1, "foo": 2};
                          return;
                        }
                        """));
        assertTrue(failure.getMessage().contains("duplicate"), failure.getMessage());
    }

    @Test
    void inferredStructPreservesDeterministicFieldOrderAtRuntime() throws Exception {
        String output = run("""
                pub routine main(): void {
                  const value = infer struct{first: 1, second: 2, third: 3};
                  stdio.stdout.write(value);
                  return;
                }
                """);
        assertEquals("infer struct{first=1, second=2, third=3}", output);
    }

    @Test
    void inferredStructCannotGainFieldsOrMutationAuthority() {
        assertThrows(IllegalArgumentException.class, () -> check("""
                fnc bad(): void {
                  const value = infer struct{foo: "bar"};
                  value.bar = 1;
                  return;
                }
                """));

        IllegalArgumentException mutation = assertThrows(
                IllegalArgumentException.class,
                () -> check("""
                        fnc bad(): void {
                          const mut value = infer struct{foo: "bar"};
                          value.foo = "baz";
                          return;
                        }
                        """));
        assertTrue(mutation.getMessage().contains("readonly")
                        || mutation.getMessage().contains("infer struct"),
                mutation.getMessage());
    }

    private static dev.oreslang.ast.Ast.Program check(String source) {
        var program = Parser.parse(source);
        TypeChecker.check(program);
        OwnershipChecker.check(program);
        return program;
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "static-struct-aot.ores")
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

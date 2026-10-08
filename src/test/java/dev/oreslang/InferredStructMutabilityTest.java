package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class InferredStructMutabilityTest {
    @Test void inferredStructRequiresReadOnlyBinding() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): string {
                  const value = infer struct{foo: "bar"};
                  return value.foo;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut value = infer struct{foo: "bar"};
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val value = infer struct{foo: "bar"};
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  let mut value = infer struct{foo: "bar"};
                  return;
                }
                """)));
    }

    @Test void inferredStructIsClosedAndFieldsCannotBeAssigned() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const value = infer struct{foo: "bar"};
                  value.foo = "changed";
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const value = infer struct{foo: "bar"};
                  value.bar = true;
                  return;
                }
                """)));
    }

    @Test void readonlyCapabilityCannotBeLaunderedThroughAlias() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const original = infer struct{foo: "bar"};
                  val alias = original;
                  alias.foo = "changed";
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const original = infer struct{foo: "bar"};
                  let mut alias = original;
                  alias.foo = "changed";
                  return;
                }
                """)));
    }

    @Test void nestedInferredStructStaysReadonlyWhenProjected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const outer = infer struct{inside: infer struct{foo: "bar"}};
                  val inner = outer.inside;
                  inner.foo = "changed";
                  return;
                }
                """)));
    }

    @Test void explicitMutableStructMayInitializeDeclaredFieldLater() throws Exception {
        String program = """
                pub routine main(): void {
                  const mut point = struct{foo: string, bar: bool}{foo: "before"};
                  point.bar = true;
                  point.foo = "after";
                  stdio.stdout.write(point.foo);
                  stdio.stdout.write(point.bar);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "explicit-struct.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("aftertrue", output.toString(StandardCharsets.UTF_8));
    }

    @Test void explicitReadonlyStructMustBeFullyInitialized() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const point = struct{foo: string, bar: bool}{foo: "before"};
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): string {
                  const point = struct{foo: string, bar: bool}{foo: "before", bar: true};
                  return point.foo;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const point = struct{foo: string}{foo: "before"};
                  point.foo = "after";
                  return;
                }
                """)));
    }

    @Test void partialExplicitStructCannotBeReadOrEscapeBeforeInitialization() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, bar: bool}{foo: "before"};
                  return point.bar;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut point = struct{foo: string, bar: bool}{foo: "before"};
                  const escaped = point;
                  return;
                }
                """)));
    }

    @Test void explicitStructRejectsUndeclaredDuplicateAndWrongTypedFields() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut point = struct{foo: string}{foo: "before"};
                  point.bar = true;
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut point = struct{foo: string}{foo: "before", bar: true};
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  const mut point = struct{foo: string, foo: string}{foo: "before"};
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const mut point = struct{foo: string}{foo: 42};
                  return;
                }
                """)));
    }

    @Test void explicitStructCompletesBeforeWholeValueUse() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): string {
                  const mut point = struct{foo: string, bar: bool}{foo: "before"};
                  point.bar = true;
                  const complete = point;
                  return complete.foo;
                }
                """)));
    }

    @Test void explicitStructWorksAcrossAsyncSuspension() throws Exception {
        String program = """
                async fnc answer(): int { return 7; }

                pub async fnc main(): void {
                  const mut point = struct{label: string, ready: bool}{label: "before"};
                  const value = await answer();
                  point.ready = value eq 7;
                  point.label = "after";
                  stdio.stdout.write(point.label);
                  stdio.stdout.write(point.ready);
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "explicit-struct-async.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("aftertrue", output.toString(StandardCharsets.UTF_8));
    }

    @Test void inferredStructRejectsDynamicKeysAndExplicitStructRequiresInitializer() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  const value = infer struct{`dynamic`: 42};
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(): void {
                  const value = struct{foo: string};
                  return;
                }
                """));
    }
}

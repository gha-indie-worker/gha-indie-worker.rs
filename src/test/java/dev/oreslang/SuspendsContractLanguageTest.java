package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SuspendsContractLanguageTest {
    private static void accepts(String code) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
    }
    private static void rejects(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(code));
    }

    @Test void asyncInvokesSuspendsAndSuspendsAwaitsAsync() throws Exception {
        String source = """
                async fnc origin(): int { return 40; }
                suspends fnc bridge(): int { return (await origin()) + 2; }
                async fnc outer(): int { return bridge(); }
                pub fnc main(): void {
                    stdio.stdout.write(await outer());
                    return;
                }
                """;
        accepts(source);
        java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "suspends.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertTrue(stdout.toString().contains("42"), stdout::toString);
    }

    @Test void rejectIllegalCallChains() {
        rejects("""
                suspends fnc inner(): int { return 7; }
                fnc invalid(): int { return inner(); }
                """);
        rejects("""
                suspends fnc inner(): int { return 7; }
                fnc middle(): int { return inner(); }
                async fnc outer(): int { return middle(); }
                """);
    }

    @Test void rejectRedundantAndInvalidBlockingContracts() {
        rejects("async suspends fnc invalid(): int { return 1; }");
        rejects("suspends async fnc invalid(): int { return 1; }");
        rejects("fnc read_blocking(): int { return 1; }");
        rejects("async fnc read_blocking(): int { return 1; }");
        accepts("suspends fnc read_blocking(): int { return 1; }");
    }

    @Test void staticMethodCallsRespectEffects() {
        accepts("""
                define class Example as
                  pub static suspends fnc get_blocking(): int { return 7; }
                  pub static async fnc obtain(): int { return Example.get_blocking(); }
                end
                """);
        rejects("""
                define class Example as
                  pub static suspends fnc get_blocking(): int { return 7; }
                  pub static fnc invalid(): int { return Example.get_blocking(); }
                end
                """);
    }

    @Test void borrowedReferenceCannotOutliveSuspendsCall() {
        rejects("""
                define class Box as
                  pub let int value = 1;
                end
                suspends fnc operation(): int { return 42; }
                suspends fnc invalid(): int {
                  let Box box = new Box();
                  val &Box view = rt borrow box;
                  val int result = operation();
                  stdio.stdout.write(view.value);
                  return result;
                }
                """);
    }
}

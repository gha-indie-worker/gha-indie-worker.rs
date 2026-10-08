package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StringTransformTest {
    @Test
    void replacementIsLiteralNonRecursiveAndPreservesUnicode() {
        assertEquals("&amp;&amp;|a$1b$1c|é😀中|aaaa|", run("""
                pub routine main(): void {
                  stdio.stdout.write("&&".replace_literal("&", "&amp;"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("a.b.c".replace_literal(".", "$1"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("é😀中".replace_literal("&", "x"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("aa".replace_literal("a", "aa"));
                  stdio.stdout.write("|");
                  stdio.stdout.write("".replace_literal("x", "y"));
                  return;
                }
                """));
    }

    @Test
    void replacementDoesNotMutateTheReceiverAndCanRemoveMatches() {
        assertEquals("ababa|Xba|a|ababa", run("""
                pub routine main(): void {
                  val string value = "ababa";
                  stdio.stdout.write(value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(value.replace_literal("aba", "X"));
                  stdio.stdout.write("|");
                  stdio.stdout.write(value.replace_literal("ab", ""));
                  stdio.stdout.write("|");
                  stdio.stdout.write(value);
                  return;
                }
                """));
    }

    @Test
    void emptyNeedleIsRejected() {
        PolyglotException error = assertThrows(PolyglotException.class,
                () -> run("""
                        pub routine main(): void {
                          stdio.stdout.write("value".replace_literal("", "x"));
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("needle must not be empty"));
    }

    @Test
    void invalidCallsAreRejectedStatically() {
        for (String expression : new String[] {
                "\"x\".replace_literal(1, \"y\")",
                "\"x\".replace_literal(\"x\")",
                "\"x\".unknown_method(\"x\", \"y\")"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(
                            "pub routine main(): void { val result = " + expression + "; return; }")));
        }
    }

    private static String run(String source) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(OresLanguage.ID, source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

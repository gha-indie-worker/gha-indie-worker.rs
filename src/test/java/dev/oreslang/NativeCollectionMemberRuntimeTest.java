package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NativeCollectionMemberRuntimeTest {

    @Test
    void nativeArrayMembersTypecheckAndExecuteWithoutHostInterop() throws Exception {
        String program = """
                pub routine main(): void {
                  let mut Array<int> values = new Array<int>();
                  values.add(4);
                  values.add(9);

                  stdio.stdout.write(values.size);
                  stdio.stdout.write("|");
                  stdio.stdout.write(values.get(0));
                  stdio.stdout.write("|");

                  values.set(1, 7);
                  stdio.stdout.write(values.get(1));

                  val pair = (11, 13);
                  stdio.stdout.write("|");
                  stdio.stdout.write(pair.size);
                  stdio.stdout.write("|");
                  stdio.stdout.write(pair.get(1));
                  return;
                }
                """;

        TypeChecker.check(Parser.parse(program));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID,
                        program,
                        "native-collection-members.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        assertEquals("2|4|7|2|13", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void listMutationChecksElementTypes() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          let Array<int> values = new Array<int>();
                          values.add("wrong");
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("collection member argument"));
    }

    @Test
    void tupleMutationIsRejectedStatically() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val pair = (1, 2);
                          pair.add(3);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("unknown collection member"));
    }
}

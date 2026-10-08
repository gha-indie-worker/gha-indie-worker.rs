package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class TelemetryPrimitivesTest {
    @Test void clocksAndIdsWorkWithoutHostAccess() {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output)
                .option("engine.WarnInterpreterOnly", "false").build()) {
            context.eval(OresLanguage.ID, """
                pub routine main(): void {
                  val int start = process.monotonic_ns();
                  val String first = process.unique_id();
                  stdio.println(process.monotonic_ns() >= start);
                  stdio.println(process.unix_ms() > 1700000000000);
                  stdio.println(first != process.unique_id());
                  return;
                }
                """);
        }
        assertEquals("true\ntrue\ntrue\n", output.toString());
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(
                "pub routine main(): void { process.monotonic_ns(1); return; }")));
    }

    @Test void unicodeEscapesCoverJsonControlsAndRejectMalformedInput() {
        var output = new ByteArrayOutputStream();
        try (var context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output)
                .option("engine.WarnInterpreterOnly", "false").build()) {
            String literal = "\\" + "u0000\\" + "u001f\\" + "uD83D\\" + "uDE00";
            context.eval(OresLanguage.ID, "pub routine main(): void { stdio.println(\"" + literal + "\"); return; }");
        }
        assertEquals("\0\u001f😀\n", output.toString());
        for (String invalid : new String[] {"u12", "uGGGG", "u１２３４"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                    "pub routine main(): void { stdio.println(\"\\" + invalid + "\"); return; }"));
        }
    }
}

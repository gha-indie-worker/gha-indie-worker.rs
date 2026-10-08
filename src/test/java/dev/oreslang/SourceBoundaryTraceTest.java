package dev.oreslang;

import dev.oreslang.runtime.SourceBoundaryTrace;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;

final class SourceBoundaryTraceTest {
    @Test
    void asyncFailureRetainsSourceNamesAcrossResumption() {
        try (var context = Context.newBuilder("ores").allowAllAccess(true).build()) {
            var error = assertThrows(PolyglotException.class, () -> context.eval("ores", """
                    async fnc leaf(): int {
                      rt cooperate;
                      val int zero = 0;
                      return 1 / zero;
                    }
                    async fnc middle(): int {
                      val int result = await leaf();
                      return result;
                    }
                    pub routine main(): void {
                      val int result = await middle();
                      stdio.println(result);
                      return;
                    }
                    """));
            var text = new StringWriter();
            error.printStackTrace(new PrintWriter(text));
            assertTrue(text.toString().contains("resumed source task leaf"), text.toString());
            assertTrue(text.toString().contains("source task middle"), text.toString());
            assertTrue(text.toString().contains("source task main"), text.toString());
        }
    }

    @Test
    void actorFailureKeepsReceiveAndAwaitingSupervisorFrames() {
        for (String kind : new String[] {"actor", "isoactor"}) {
            try (var context = Context.newBuilder("ores").allowAllAccess(true).build()) {
                var error = assertThrows(PolyglotException.class, () -> context.eval("ores", """
                        define %s Broken as
                          receive(ActorMail<String> mail): void {
                            rt cooperate;
                            val Option<int> empty = None;
                            stdio.println(empty.unwrap());
                            return;
                          }
                        end
                        pub routine main(): void {
                          val worker = spawn Broken();
                          await worker.ready;
                          worker.send("go");
                          await worker.done;
                          return;
                        }
                        """.formatted(kind)));
                var text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                assertTrue(text.toString().contains("source task receive"), text.toString());
                assertTrue(text.toString().contains("source task main"), text.toString());
            }
        }
    }

    @Test
    void diagnosticsAreBoundedAndPreserveFailureIdentity() {
        var cause = new IllegalStateException("native failure");
        for (int i = 0; i < 1000; i++) {
            assertSame(cause, SourceBoundaryTrace.record(cause, "test.ores", "caller", "await"));
        }
        assertEquals(64, cause.getSuppressed().length);
        assertEquals("native failure", cause.getMessage());
        assertEquals(0, cause.getSuppressed()[0].getStackTrace().length);
    }
}

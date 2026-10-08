package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class FailureEffectLanguageTest {
    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "failure-effects.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void throwIsCatchableAndTrapProducesNone() throws Exception {
        String source = """
                trap fnc fails(): int {
                  throw "ordinary";
                }
                pub fnc main(): void {
                  try {
                    throw "caught";
                  } catch (err) {
                    stdio.stdout.write(err);
                  }
                  val Option<int> absent = fails();
                  stdio.stdout.write(":");
                  stdio.stdout.write(absent.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("caught:true", run(source));
    }

    @Test
    void raiseBypassesCatchButRecoverHandlesOnlyTheRaise() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      raise "raised";
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err);
                  }
                  stdio.stdout.write(":after");
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("raised:after", run(source));
    }

    @Test
    void nestedPayloadCallsRemainReachableThroughStaticPasses() throws Exception {
        String source = """
                fnc payload(): string {
                  return "kept";
                }
                pub fnc main(): void {
                  rt recover {
                    raise payload();
                  } on raise (Error err) {
                    stdio.stdout.write(err);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("kept", run(source));
    }

    @Test
    void panicCannotBeRecoveredOrCaught() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      panic "actor corrupt";
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write("wrong");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failed = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failed.getMessage().contains("actor corrupt"));
    }

    @Test
    void strongerRaiseSurvivesFinallyAndDeferThrows() throws Exception {
        String source = """
                fnc cleanup(): void {
                  throw "cleanup";
                }
                pub fnc main(): void {
                  rt recover {
                    defer cleanup();
                    try {
                      raise "strong";
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    } finally {
                      throw "weaker";
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("strong", run(source));
    }

    @Test
    void panicInFinallyOutranksAnEarlierRaise() throws Exception {
        String source = """
                pub fnc main(): void {
                  rt recover {
                    try {
                      raise "earlier raise";
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    } finally {
                      panic "new panic";
                    }
                  } on raise (Error err) {
                    stdio.stdout.write("wrong");
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        PolyglotException failed = assertThrows(PolyglotException.class, () -> run(source));
        assertTrue(failed.getMessage().contains("new panic"));
    }

    @Test
    void raiseAfterCooperativeSuspensionSkipsCatchAndReachesRecover() throws Exception {
        String source = """
                pub async routine main(): void {
                  rt recover {
                    try {
                      rt cooperate;
                      raise "after-suspension";
                    } catch (err) {
                      stdio.stdout.write("wrong");
                    }
                  } on raise (Error err) {
                    stdio.stdout.write(err);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("after-suspension", run(source));
    }

    @Test
    void deferredCleanupThrowCannotDowngradeSuspendedRaise() throws Exception {
        String source = """
                fnc cleanup(): void {
                  throw "cleanup";
                }
                pub async routine main(): void {
                  rt recover {
                    defer cleanup();
                    rt cooperate;
                    raise "preserved";
                  } on raise (Error err) {
                    stdio.stdout.write(err);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("preserved", run(source));
    }

    @Test
    void bareFailuresAreRejectedByParser() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("pub fnc main(): void { throw; }"));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.parseAndTypeCheck("pub fnc main(): void { panic; }"));
    }
}

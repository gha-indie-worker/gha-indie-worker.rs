package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectSourceFlowSafetyTest {
    @Test
    void discardedReturnRunsArmDeferAndContinuesExactlyOnce() throws Exception {
        String source = """
                pub fnc main(): void {
                  do select {
                    default: {
                      defer stdio.stdout.write("cleanup:");
                      stdio.stdout.write("arm:");
                      return 77;
                    }
                  }
                  stdio.stdout.write("continuing");
                  return;
                }
                """;
        assertWarnings(source, 1);
        assertEquals("arm:cleanup:continuing", run(source));
    }

    @Test
    void legacySelectedReturnPropagatesThroughTryFinallyExactlyOnce() throws Exception {
        String source = """
                pub fnc compute(): int {
                  try {
                    select {
                      default: {
                        return 5;
                      }
                    }
                  } catch (err) {
                    stdio.stdout.write("unexpected-catch:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  return 99;
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("finally:5", run(source));
    }

    @Test
    void discardedArmReturnPreservesItsOwnTryFinally() throws Exception {
        String source = """
                pub fnc main(): void {
                  do select {
                    default: {
                      try {
                        stdio.stdout.write("arm:");
                        return 11;
                      } catch (err) {
                        stdio.stdout.write("unexpected:");
                      } finally {
                        stdio.stdout.write("finally:");
                      }
                    }
                  }
                  stdio.stdout.write("continuing");
                  return;
                }
                """;
        assertWarnings(source, 1);
        assertEquals("arm:finally:continuing", run(source));
    }

    @Test
    void channelCloseIsVisibleToSourceAndPreservesCommittedBufferedValue() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 42;
                  stdio.stdout.write(channel.is_closed());
                  channel.close();
                  stdio.stdout.write(":");
                  stdio.stdout.write(channel.is_closed());
                  stdio.stdout.write(":");
                  stdio.stdout.write(readch channel);
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("false:true:42", run(source));
    }

    @Test
    void selectedClosedChannelErrorIsCaughtOnce() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> closed = Channel.new<int>(1);
                  closed.close();
                  try {
                    do select {
                      case readch closed: val value {
                        stdio.stdout.write("impossible");
                      }
                    }
                  } catch (err) {
                    stdio.stdout.write("caught:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  stdio.stdout.write("continuing");
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("caught:finally:continuing", run(source));
    }

    @Test
    void errorAfterSelectedArmDoesNotReplayEarlierArmOrCatch() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> closed = Channel.new<int>(0);
                  closed.close();
                  try {
                    do select {
                      default: {
                        stdio.stdout.write("selected:");
                      }
                    }
                    stdio.stdout.write("after:");
                    readch closed;
                  } catch (err) {
                    stdio.stdout.write("caught:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("selected:after:caught:finally:", run(source));
    }

    @Test
    void immediateLegacySelectStillPropagatesFunctionReturn() throws Exception {
        String source = """
                pub fnc compute(): int {
                  try select {
                    default: {
                      return 13;
                    }
                  }
                  return 99;
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertWarnings(source, 0);
        assertEquals("13", run(source));
    }

    private static void assertWarnings(String source, int count) {
        var result = OresCompiler.parseAndTypeCheckWithDiagnostics(source);
        assertEquals(count, result.warnings().size(), result.warnings().toString());
        result.warnings().forEach(w -> assertTrue(w.contains("W-SELECT-RETURN"), w));
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "select-flow-safety.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

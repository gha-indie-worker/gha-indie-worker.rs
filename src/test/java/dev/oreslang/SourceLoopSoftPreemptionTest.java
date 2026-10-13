package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
final class SourceLoopSoftPreemptionTest {
    @Test
    void asyncForLoopRunsThousandsOfIterationsWithoutStackGrowthOrReplay() throws Exception {
        String output = run("""
                async fnc count(): int {
                  let total = 0;
                  for (let i = 0; i < 10000; i = i + 1) {
                    total = total + 1;
                  }
                  return total;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("10000|"), output);
        long cooperates = Long.parseLong(output.substring("10000|".length()));
        assertTrue(cooperates > 0, "CPU-only source loops must release their carrier at least once");
    }

    @Test
    void nestedIfAndLoopPreserveContinueBreakAndMutableLocals() throws Exception {
        assertEquals("667", run("""
                async fnc count(): int {
                  let count = 0;
                  let i = 0;
                  if true; do
                    loop do
                      i = i + 1;
                      if i % 3 == 0; do
                        continue;
                      fi
                      count = count + 1;
                      if i == 1000; do
                        break;
                      fi
                    done
                  fi
                  return count;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  return;
                }
                """));
    }

    @Test
    void nestedForOfDoesNotRepeatConsumedIteratorElementsAfterCooperate() throws Exception {
        assertEquals("2400", run("""
                async fnc count(): int {
                  let total = 0;
                  for (let i = 0; i < 600; i = i + 1) do
                    for const item of arr[1, 2, 3, 4] do
                      total = total + 1;
                    done
                  done
                  return total;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(await count());
                  return;
                }
                """));
    }

    @Test
    void synchronousReceiveLoopAutoPreemptsWithoutOvertakingLaterMailboxMessages()
            throws Exception {
        String output = run("""
                define actor Worker as
                  let int count;

                  on_start(): void {
                    self.count = 0;
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
                      for (let i = 0; i < 4000; i = i + 1) do
                        self.count = self.count + 1;
                      done
                      self.send(self.count);
                      return;
                    fi
                    if mail.value == "second"; do
                      self.send(self.count + 1);
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("first");
                  worker.send("second");
                  val first = (await worker.outputs.next()).value.unwrap();
                  val second = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  await worker.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """);
        assertTrue(output.startsWith("4000|4001|"), output);
        long cooperates = Long.parseLong(output.substring("4000|4001|".length()));
        assertTrue(cooperates > 0, "synchronous actor receive must be source-task preemptible");
    }

    @Test
    void automaticLoopCooperationDoesNotYieldWhileMutexGuardIsLive() throws Exception {
        assertEquals("2000|0", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  val guard = mutex.lock();
                  for (let i = 0; i < 2000; i = i + 1) do
                    guard.value = guard.value + 1;
                  done
                  stdio.stdout.write(guard.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  guard.release();
                  return;
                }
                """));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-loop-quantum.ores")
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

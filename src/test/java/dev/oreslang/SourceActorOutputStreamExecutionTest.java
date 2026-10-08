package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SourceActorOutputStreamExecutionTest {

    @Test
    void sourceActorStreamsOrderedOutputsAndClosesAfterFinalization() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("hello");
                    self.send("world");
                    self.send("!");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("go");

                  val firstStep = await worker.outputs.next();
                  val first = firstStep.value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(first.sequence);

                  val secondStep = await worker.outputs.next();
                  val second = secondStep.value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(second.sequence);

                  val thirdStep = await worker.outputs.next();
                  val third = thirdStep.value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(third.value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(third.sequence);

                  await worker.done;

                  val terminal = await worker.outputs.next();
                  stdio.stdout.write("|");
                  stdio.stdout.write(terminal.done);
                  return;
                }
                """;

        assertEquals(
                "hello:0|world:1|!:2|true",
                run(program, "source-actor-output-stream.ores"));
    }

    @Test
    void forAwaitConsumesActorOutputsUntilActorFinalization() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("hello");
                    self.send("world");
                    self.send("!");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("go");

                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }

                  await worker.done;
                  return;
                }
                """;

        assertEquals(
                "helloworld!",
                run(program, "source-actor-output-for-await.ores"));
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, name)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

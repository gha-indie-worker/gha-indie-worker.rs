package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
final class MemberSuspensionLanguageTest {
    @TempDir Path temp;

    @Test
    void importedPolymorphicPullMethodsResumeTheirCallers() throws Exception {
        Files.writeString(temp.resolve("library.ores"), """
                define module library
                  pub define class Pull as
                    pub next(): int { return self.pull(); }
                    pub pull(): int { return 0; }
                  end
                  pub define class ChannelPull extends Pull as
                    val Channel<int> input;
                    pub constructor(Channel<int> input) { self.input = input; }
                    pub pull(): int { return await (nb readch self.input); }
                  end
                end
                """);
        assertEquals("42", run("""
                import class Pull, ChannelPull from './library.ores';
                define module app
                  pub async fnc main(): void {
                    val Channel<int> input = Channel.new<int>(0);
                    val producing = nb writech input, 42;
                    val ChannelPull reader = new ChannelPull(input);
                    val int value = reader.next();
                    stdio.println(value);
                    await producing;
                    return;
                  }
                end
                """));
    }

    @Test
    void importedReceiverNamedLikeBuiltinNamespaceStillSuspends() throws Exception {
        Files.writeString(temp.resolve("library.ores"), """
                define module library
                  pub define class Pull as
                    pub next(): int { return self.pull(); }
                    pub pull(): int { return 0; }
                  end
                  pub define class ChannelPull extends Pull as
                    val Channel<int> input;
                    pub constructor(Channel<int> input) { self.input = input; }
                    pub pull(): int { return await (nb readch self.input); }
                  end
                end
                """);
        assertEquals("43", run("""
                import class Pull, ChannelPull from './library.ores';
                define module app
                  fnc consume(ChannelPull Math): int {
                    val int value = Math.next();
                    return value + 1;
                  }
                  pub async fnc main(): void {
                    val Channel<int> input = Channel.new<int>(0);
                    val producing = nb writech input, 42;
                    val ChannelPull reader = new ChannelPull(input);
                    val int value = consume(reader);
                    stdio.println(value);
                    await producing;
                    return;
                  }
                end
                """));
    }

    @Test
    void declaredFutureMethodsAndFunctionsRemainRegisterAndReturn() throws Exception {
        assertEquals("42\n43", run("""
                define module app
                  fnc pending(Channel<int> input): Future<int> { return nb readch input; }
                  define class Reader as
                    val Channel<int> input;
                    constructor(Channel<int> input) { self.input = input; }
                    pub pending(): Future<int> { return nb readch self.input; }
                  end
                  pub async fnc main(): void {
                    val Channel<int> input = Channel.new<int>(0);
                    val reader = new Reader(input);
                    val Future<int> first = reader.pending();
                    writech input, 42;
                    stdio.println(await first);
                    val Future<int> second = pending(input);
                    writech input, 43;
                    stdio.println(await second);
                    return;
                  }
                end
                """));
    }

    @Test
    void builtinIteratorFutureDoesNotBecomeAnImplicitAwait() throws Exception {
        assertEquals("ok", run("""
                define module app
                  async generator fnc values(): int {
                    yield 44;
                    return;
                  }
                  pub async fnc main(): void {
                    val iterator = values();
                    val Future<IteratorResult<int>> pending = iterator.next();
                    val group = Future.all([pending]);
                    await group;
                    stdio.println("ok");
                    iterator.close();
                    return;
                  }
                end
                """));
    }

    private String run(String program) throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, program);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}

package dev.oreslang;

import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NominalIdentityBoundaryTest {
    @TempDir Path temp;

    @Test
    void classIdentityCannotBeForgedByNameOrForeignParent() throws Exception {
        Files.writeString(temp.resolve("library.ores"), """
                pub define class Token as
                end
                define class RealChild extends Token as
                end
                define module guards as
                  pub fnc make(): Token { return new Token(); }
                  pub fnc child(): Token { return new RealChild(); }
                  pub fnc accepts(Token value): bool {
                    return (value as? Token).is_some();
                  }
                end
                """);
        assertEquals("true|true|false|false", run("""
                import module guards from "./library";
                define class Token as
                end
                define class ForeignChild extends Token as
                end
                pub routine main(): void {
                  stdio.stdout.write(guards.accepts(guards.make()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(guards.child()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(new Token()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(new ForeignChild()));
                  return;
                }
                """));
    }

    @Test
    void interfaceIdentityCannotBeForgedByNameOrForeignExtension() throws Exception {
        Files.writeString(temp.resolve("library.ores"), """
                define interface Tag as
                end
                define interface Extended extends Tag as
                end
                define class Real implements Tag as
                end
                define class RealChild implements Extended as
                end
                define module guards as
                  pub fnc make(): Tag { return new Real(); }
                  pub fnc child(): Tag { return new RealChild(); }
                  pub fnc accepts(Tag value): bool {
                    return (value as? Tag).is_some();
                  }
                end
                """);
        assertEquals("true|true|false|false", run("""
                import module guards from "./library";
                define interface Tag as
                end
                define interface Extended extends Tag as
                end
                define class Foreign implements Tag as
                end
                define class ForeignChild implements Extended as
                end
                pub routine main(): void {
                  stdio.stdout.write(guards.accepts(guards.make()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(guards.child()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(new Foreign()));
                  stdio.stdout.write("|");
                  stdio.stdout.write(guards.accepts(new ForeignChild()));
                  return;
                }
                """));
    }

    private String run(String source) throws Exception {
        Path entry = temp.resolve("main.ores");
        Files.writeString(entry, source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                Set.of(), Map.of(), output, new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}

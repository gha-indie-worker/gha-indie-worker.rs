package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorPseudoHandlerAdmissionTest {
    @Test
    void actorPseudoHandlersAreRejectedUntilCompilerCheckedSupervisionExists() {
        for (String name : new String[] {
                "on_throw", "on_throws", "on_raise", "on_panic", "on_recover"
        }) {
            String source = "define actor Worker as\n"
                    + "  pub " + name + "(): void { return; }\n"
                    + "end\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), name);
            assertTrue(error.getMessage().contains("compiler-enforced exhaustiveness"),
                    () -> name + ": " + error.getMessage());
        }
    }

    @Test
    void OrdinaryNonActorMethodsAndActorMethodsAreUnaffected() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Example as
                  pub on_panic(): void { return; }
                end
                define actor Worker as
                  pub run(): void { return; }
                end
                """));
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.TaggedMessageCodec;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-backend contract for compile-time closed enum expansion. */
final class MatchPatternEnumSpreadTest {
    private static final String STATUS = """
            define enum Status as
                Message1: Tuple[bool, string]
                Message2: string
                Message3: string
            end
            """;

    private static TaggedMessageContracts.Registry registry(String source) {
        return TaggedMessageContracts.verify(Parser.parse(source));
    }

    @Test
    void canonicalMatchPatternSpreadsWholeEnumInDeclarationOrder() {
        var expanded = registry(STATUS + """
                define match pattern Messages as
                    ...Status
                end
                """);
        var explicit = registry(STATUS + """
                define match pattern Messages as
                    Status.Message1
                    Status.Message2
                    Status.Message3
                end
                """);
        assertEquals(List.of("Status.Message1", "Status.Message2", "Status.Message3"),
                expanded.requireDomain("Messages").cases().stream()
                        .map(TaggedMessageContracts.Case::qualifiedName).toList());
        assertEquals(explicit.requireDomain("Messages"), expanded.requireDomain("Messages"));

        // The same closed domain must produce the same canonical v2 wire ID.
        var value = new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello");
        var spreadCodec = new TaggedMessageCodec(expanded.requireDomain("Messages"));
        var explicitCodec = new TaggedMessageCodec(explicit.requireDomain("Messages"));
        assertArrayEquals(explicitCodec.encode(value), spreadCodec.encode(value));
        assertEquals(value, spreadCodec.decode(spreadCodec.encode(value)));
    }

    @Test
    void spreadSupportsForwardDeclarationsAndLegacyPatternSpelling() {
        var forward = registry("""
                define match pattern Messages as
                    ...Status
                end
                """ + STATUS);
        assertEquals(3, forward.requireDomain("Messages").cases().size());
        var legacy = registry(STATUS + """
                define pattern Messages as
                    ...Status
                end
                """);
        assertEquals(forward.requireDomain("Messages"), legacy.requireDomain("Messages"));
    }

    @Test
    void enumGrowthAutomaticallyUpdatesExpandedDomain() {
        var before = registry(STATUS + """
                define match pattern Messages as ...Status end
                """);
        var after = registry(STATUS.replace(
                "Message3: string", "Message3: string\n    Message4: bool") + """
                define match pattern Messages as ...Status end
                """);
        assertEquals(3, before.requireDomain("Messages").cases().size());
        assertEquals(4, after.requireDomain("Messages").cases().size());
        assertEquals("Status.Message4",
                after.requireDomain("Messages").cases().get(3).qualifiedName());
    }

    @Test
    void duplicateExplicitAndSpreadCasesFailRatherThanSilentlyDeduplicating() {
        assertThrows(IllegalArgumentException.class, () -> registry(STATUS + """
                define match pattern Bad as
                    Status.Message2
                    ...Status
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> registry(STATUS + """
                define match pattern Bad as
                    ...Status
                    ...Status
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> registry(STATUS + """
                define match pattern Bad as ...Missing end
                """));
        assertThrows(IllegalArgumentException.class, () -> registry(STATUS + """
                define match pattern Good as ...Status end
                define match pattern Bad as ...Good end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(STATUS + """
                define match pattern Bad as ...Status.Message1 end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(STATUS + """
                define match Bad as ...Status end
                """));
    }

    @Test
    void expansionRejectsOverfullCombinedDomain() {
        List<Ast.EnumCase> cases = new ArrayList<>();
        for (int i = 1; i <= 4096; i++) {
            cases.add(new Ast.EnumCase("V" + i, null, i));
        }
        var program = new Ast.Program(List.of(new Ast.ModuleDecl(
                Parser.ROOT_MODULE, List.of(
                new Ast.EnumDecl("Big", cases),
                new Ast.EnumDecl("Small", List.of(new Ast.EnumCase("Only", null, 1))),
                new Ast.PatternDecl("TooBig", List.of("...Big", "Small.Only"))))));
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(program));
    }

    @Test
    void actorMailboxTypeCheckerAcceptsSpreadDomainAndRejectsUndeclaredVariant() {
        String source = STATUS + """
                define match pattern Messages as
                    ...Status
                end
                define actor Worker as
                    receive(ActorMail<Messages> mail): void {
                        self.end();
                        return;
                    }
                end
                pub async routine main(): void {
                    val worker = await spawn Worker();
                    worker.send(Status.Message2("hello"));
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(source.replace(
                        "worker.send(Status.Message2(\"hello\"));",
                        "worker.send(42 as Status.Message2);"))));
    }
}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumPayloadDependencies;
import dev.oreslang.types.TaggedMessageContracts;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class EnumPayloadDependenciesTest {
    @Test void directSelfContainmentRejectedWithoutAutoBoxing() {
        var source = Parser.parse("""
            define enum Foo as
                Empty
                Message1: Foo
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
        assertTrue(error.getMessage().contains("unboxed recursive enum payload"), error.toString());
        assertTrue(error.getMessage().contains("Foo -> Foo"), error.toString());
    }

    @Test void mutualAndTupleRecursionRejectedIndependentOfDeclarationOrder() {
        var mutual = Parser.parse("""
            define enum Bar as
                ToFoo: Foo
            end
            define enum Foo as
                ToBar: Bar
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(mutual));
        assertTrue(error.getMessage().contains("Bar -> Foo -> Bar"), error.toString());
        var tuple = Parser.parse("""
            define enum Foo as
                Empty
                Message1: Tuple[int, Foo]
            end
            """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(tuple))
                .getMessage().contains("Foo -> Foo"));
    }

    @Test void distinctNestedEnumTypeGetsFiniteClosedWireSchema() {
        var source = Parser.parse("""
            define enum Priority as
                Low
                High
            end
            define enum Foo as
                Message1: Priority
            end
            """);
        var references = EnumPayloadDependencies.check(source);
        assertEquals(1, references.size());
        assertEquals("Priority", references.getFirst().to());
        assertTrue(references.getFirst().byValue());
        var registry = assertDoesNotThrow(() -> TaggedMessageContracts.verify(source));
        assertInstanceOf(dev.oreslang.runtime.MessagePackCollectionCodec.NestedEnum.class,
                registry.requireCase("Foo.Message1").payload());
    }

    @Test void explicitIndirectionBreaksSizeCycleButDoesNotCreateWireSupport() {
        var source = Parser.parse("""
            define enum Foo as
                Empty
                More: List<Foo>
            end
            """);
        var references = EnumPayloadDependencies.check(source);
        assertEquals(1, references.size());
        assertFalse(references.getFirst().byValue());
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source))
                .getMessage().contains("recursive enum wire schema"));
    }

    @Test void twoFooDeclarationsCannotBeMergedOrSilentlyShadowed() {
        var source = Parser.parse("""
            define enum Foo as
                Message1 = 1
            end
            define enum Foo as
                Message1: Foo
            end
            """);
        var error = assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
        assertTrue(error.getMessage().contains("duplicate enum declaration"), error.toString());
    }

    @Test void exhaustiveThreeEnumDependencyGraphsAgreeWithTransitiveClosure() {
        // This is bounded exhaustive graph checking (2^9 directed graphs),
        // not a proof of arbitrary-size compiler implementation refinement.
        for (int mask = 0; mask < (1 << 9); mask++) {
            boolean[][] reachable = new boolean[3][3];
            var decls = new java.util.ArrayList<Ast.Decl>();
            for (int i = 0; i < 3; i++) {
                var cases = new java.util.ArrayList<Ast.EnumCase>();
                cases.add(new Ast.EnumCase("Empty", null, 1));
                for (int j = 0; j < 3; j++) {
                    if ((mask & (1 << (i * 3 + j))) != 0) {
                        reachable[i][j] = true;
                        cases.add(new Ast.EnumCase("To" + j,
                                Ast.TypeRef.simple("E" + j), j + 2));
                    }
                }
                decls.add(new Ast.EnumDecl("E" + i, cases));
            }
            for (int k = 0; k < 3; k++)
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++)
                        reachable[i][j] |= reachable[i][k] && reachable[k][j];
            boolean cycle = reachable[0][0] || reachable[1][1] || reachable[2][2];
            var ast = new Ast.Program(List.of(new Ast.ModuleDecl(
                    Parser.ROOT_MODULE, decls)));
            boolean rejected = false;
            try {
                EnumPayloadDependencies.check(ast);
            } catch (IllegalArgumentException failure) {
                assertTrue(failure.getMessage().contains(
                        "unboxed recursive enum payload"), failure.toString());
                rejected = true;
            }
            assertEquals(cycle, rejected, "directed adjacency mask " + mask);
        }
    }

    @Test void regularEnumsAndMatchPatternSpreadsRemainUnaffected() {
        var source = Parser.parse("""
            define enum Status as
                Message1: Tuple[bool, string]
                Message2: string
                Message3: string
            end
            define match pattern Messages as
                ...Status
            end
            """);
        assertTrue(EnumPayloadDependencies.check(source).isEmpty());
        assertEquals(3, TaggedMessageContracts.verify(source)
                .requireDomain("Messages").cases().size());
    }
}

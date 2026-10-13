package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.NominalEnumValueGuards;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.runtime.TaggedMessageCodec;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class NestedEnumLocalValueTest {
    private static final String DECLS = """
            define enum Bar as
                Message1 = 1
            end
            define enum Foo as
                Message1: Bar
            end
            """;

    @Test
    void nestedLocalEnumsMayContainWidthCheckedPayloadsWithoutWireCodec() {
        String source = """
                define enum Outer as
                    Wrapped: Inner
                    Empty
                end
                define enum Inner as
                    Byte: u8
                    None
                end
                """;
        var program = Parser.parse(source);
        var types = TaggedMessageContracts.forTypes(program);
        assertNull(types.requireCase("Inner.Byte").payload());
        assertNull(types.requireCase("Outer.Wrapped").payload(),
                "an outer enum must never inherit a fake nested wire codec");
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(program));

        var inner = new TaggedMessageCodec.TaggedValue("Inner", "Byte", 1, 255L);
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(
                types, types.requireCase("Outer.Wrapped"), inner));
        var forged = new TaggedMessageCodec.TaggedValue("Inner", "Byte", 1, 256L);
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(
                        types, types.requireCase("Outer.Wrapped"), forged));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source + """
                fnc inspect(Outer outer): int {
                    match outer over
                        on Outer.Wrapped as Inner.Byte(v) -> { return v; }
                        on Outer.Wrapped as Inner.None -> { return 0; }
                        on Outer.Empty -> { return -1; }
                        on _ -> { return -2; }
                    end
                }
                """)));
    }

    @Test
    void nestedLocalCollectionsRemainValueSafeAndWireIneligible() {
        String source = """
                define enum Inner as
                    Byte: u8
                end
                define enum Outer as
                    Batch: List<Inner>
                end
                """;
        var program = Parser.parse(source);
        var types = TaggedMessageContracts.forTypes(program);
        assertNull(types.requireCase("Outer.Batch").payload());
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(program));
        Object good = List.of(
                new TaggedMessageCodec.TaggedValue("Inner", "Byte", 1, 0L),
                new TaggedMessageCodec.TaggedValue("Inner", "Byte", 1, 255L));
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(
                types, types.requireCase("Outer.Batch"), good));
        Object invalid = List.of(
                new TaggedMessageCodec.TaggedValue("Inner", "Byte", 1, 256L));
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(
                        types, types.requireCase("Outer.Batch"), invalid));
    }

    @Test
    void recursiveCollectionEnumsRemainLocalAndRejectCyclicHostGraphs() {
        String source = """
                define enum Chain as
                    Stop
                    Next: List<Chain>
                end
                """;
        var program = Parser.parse(source);
        var types = TaggedMessageContracts.forTypes(program);
        assertNull(types.requireCase("Chain.Next").payload());
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(program));

        var stop = new TaggedMessageCodec.TaggedValue("Chain", "Stop", 1, null);
        var next = new TaggedMessageCodec.TaggedValue("Chain", "Next", 2, List.of(stop));
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(
                types, types.requireCase("Chain.Next"), List.of(next)));

        var mutable = new java.util.ArrayList<Object>();
        var cyclic = new TaggedMessageCodec.TaggedValue("Chain", "Next", 2, mutable);
        mutable.add(cyclic);
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumValueGuards.requirePayload(
                        types, types.requireCase("Chain.Next"), mutable));
    }

    @Test
    void nestedLocalWidthEnumsAreNotSerializableActorMessages() {
        String source = """
                define enum Inner as
                    Byte: u8
                end
                define enum Outer as
                    Wrapped: Inner
                end
                define actor Worker as
                    receive(ActorMail<Outer> mail): void {
                        self.end();
                        return;
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nominalNestedLocalCasesRetainPayloadTypeAndAreNotWireValuesYet() {
        var program = Parser.parse(DECLS);
        var registry = TaggedMessageContracts.forTypes(program);
        var bar = registry.requireCase("Bar.Message1");
        var foo = registry.requireCase("Foo.Message1");
        assertNull(bar.sourcePayload());
        assertNull(bar.payload());
        assertEquals("Bar", foo.sourcePayload().name());
        assertInstanceOf(dev.oreslang.runtime.MessagePackCollectionCodec.NestedEnum.class,
                foo.payload());
        assertEquals(1, foo.tag());
        assertDoesNotThrow(() -> TaggedMessageContracts.verify(program));
        assertDoesNotThrow(() -> new TaggedMessageCodec(registry.requireDomain("Foo")));
        // Constructed values are tagged instances, not their wire integer tags.
        var inner = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null);
        var outer = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, inner);
        assertNotEquals(inner, outer);
        assertNotEquals(1, inner);
        assertEquals(inner, outer.payload());
    }

    @Test
    void typeCheckerAcceptsConstructingBarInsideFooButRejectsWrongNominalEnum() {
        var valid = DECLS + """
                pub routine main(): void {
                    val inner = Bar.Message1();
                    val outer = Foo.Message1(inner);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "val outer = Foo.Message1(inner);",
                        "val outer = Foo.Message1(7);"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "val outer = Foo.Message1(inner);",
                        "val outer = Foo.Message1(Foo.Message1(inner));"))));
    }

    @Test
    void sourceRuntimeConstructsNestedNominalCaseLocally() throws Exception {
        var program = DECLS + """
                pub routine main(): void {
                    val outer = Foo.Message1(Bar.Message1());
                    if outer == Foo.Message1(Bar.Message1()); do
                        stdio.stdout.write("nested-ok");
                    fi
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var buffer = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-enum-local.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(buffer).build()) {
            context.eval(source);
        }
        assertEquals("nested-ok", buffer.toString(StandardCharsets.UTF_8));
    }

    @Test
    void actorMailboxAdmitsFiniteNestedEnumAndStillRejectsBoxedRecursion() {
        var source = DECLS + """
                define actor Worker as
                    receive(ActorMail<Foo> mail): void {
                        self.end();
                        return;
                    }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        var boxedSource = """
                define enum Recursive as
                    Empty
                    More: Box<Recursive>
                end
                define actor Worker as
                    receive(ActorMail<Recursive> mail): void {
                        self.end();
                        return;
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(boxedSource)));
    }

    @Test
    void nestedRuntimeConstructorRejectsForgedTagOwnerAndNullaryPayload() {
        var registry = TaggedMessageContracts.forTypes(Parser.parse(DECLS));
        var foo = registry.requireCase("Foo.Message1");
        var good = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null);
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(registry, foo, good));

        var forgedTag = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 99, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, forgedTag))
                .getMessage().contains("forged"));
        var wrongOwner = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, good);
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, wrongOwner))
                .getMessage().contains("nominal owner"));
        var forgedNullary = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, "smuggled");
        assertTrue(assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, forgedNullary))
                .getMessage().contains("nullary"));
    }

    @Test
    void nestedEnumInsideTupleChecksAllNominalElementsAndRejectsCycle() {
        var parsed = Parser.parse("""
                define enum Bar as
                    Ready
                end
                define enum Foo as
                    Payload: Tuple[int, Bar]
                end
                """);
        var registry = TaggedMessageContracts.forTypes(parsed);
        var foo = registry.requireCase("Foo.Payload");
        var valid = List.of(42L, new TaggedMessageCodec.TaggedValue("Bar", "Ready", 1, null));
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(registry, foo, valid));
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo,
                        List.of(42L, new TaggedMessageCodec.TaggedValue("Foo", "Payload", 1, valid))));
        var cyclic = new java.util.ArrayList<Object>();
        cyclic.add(42L);
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry, foo, cyclic));
    }

    @Test
    void rejectsMalformedBoxArityAtDependencyBoundary() {
        for (String type : List.of("Box<>", "Box<Bar, Foo>")) {
            var source = Parser.parse(DECLS + """
                    define enum Holder as
                        More: %s
                    end
                    """.formatted(type));
            var problem = assertThrows(IllegalArgumentException.class,
                    () -> TaggedMessageContracts.forTypes(source));
            assertTrue(problem.getMessage().contains("Box<T> requires exactly one"),
                    problem.toString());
        }
    }

    @Test
    void nestedActorWireRoundTripAndNominalTamperRejection() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Foo"));
        var inner = new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null);
        var outer = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, inner);
        var bytes = codec.encode(outer);
        assertEquals(outer, codec.decode(bytes));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1,
                        new TaggedMessageCodec.TaggedValue("Bar", "Message1", 3, null))));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1,
                        new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1, inner))));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                java.util.Arrays.copyOf(bytes, bytes.length - 1)));

        assertTrue(java.util.HexFormat.of().formatHex(bytes).contains("a3426172"),
                "nested wire contains canonical Bar owner name");
    }

    @Test
    void compiledNestedWireIsIdenticalToIndependentMessagePackGolden() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Foo"));
        var value = new TaggedMessageCodec.TaggedValue("Foo", "Message1", 1,
                new TaggedMessageCodec.TaggedValue("Bar", "Message1", 1, null));
        // Independent reference: SHA-256 over the canonical finite enum
        // descriptor, MessagePack v2 [2,schema,shape,[Foo,1,[Bar,1]]].
        String expected = "9402d98f6f7265736c616e672e656e756d2e306162326236366364343839"
                + "373130373332323563653264353938323735336137343830326636653261316437393861"
                + "306632646364386665383233643232612e326434356430383134646535323635373039"
                + "326261626636363331323437633761623136303164643035373935646432616534323833"
                + "65303665383036373039c42071605bdfa3efdbcb5fe7aa4d4230f049269d4cd630b"
                + "72c8c27f452c9b09ea42093a3466f6f0192a342617201";
        assertEquals(expected, java.util.HexFormat.of().formatHex(codec.encode(value)));
        assertEquals(value, codec.decode(java.util.HexFormat.of().parseHex(expected)));
    }

    @Test
    void sourceActorAcceptsNestedEnumAcrossBinaryMailbox() throws Exception {
        var program = DECLS + """
                define actor Worker as
                    receive(ActorMail<Foo> mail): void {
                        self.end();
                        return;
                    }
                end

                pub async routine main(): void {
                    val worker = await spawn Worker();
                    worker.send(Foo.Message1(Bar.Message1()));
                    await worker.get_done();
                    stdio.stdout.write("nested-actor-done");
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
        var output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "nested-enum-actor.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("nested-actor-done", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void boundedCrossVariantRoundTripsAndInnerEnumEvolutionFencesWire() {
        String original = """
                define enum Bar as
                    A = 1
                    B = 3
                    C = 9
                end
                define enum Foo as
                    Wrap: Bar
                    Text: string
                end
                define match pattern Messages as
                    ...Foo
                end
                """;
        var registry = TaggedMessageContracts.verify(Parser.parse(original));
        var oldCodec = new TaggedMessageCodec(registry.requireDomain("Messages"));
        for (var caseInfo : registry.enums().get("Bar").cases()) {
            var inner = new TaggedMessageCodec.TaggedValue(
                    "Bar", caseInfo.name(), caseInfo.tag(), null);
            var outer = new TaggedMessageCodec.TaggedValue("Foo", "Wrap", 1, inner);
            assertEquals(outer, oldCodec.decode(oldCodec.encode(outer)));
        }
        var grown = TaggedMessageContracts.verify(Parser.parse(
                original.replace("C = 9", "C = 9\n    D = 12")));
        var newCodec = new TaggedMessageCodec(grown.requireDomain("Messages"));
        var old = new TaggedMessageCodec.TaggedValue("Foo", "Wrap", 1,
                new TaggedMessageCodec.TaggedValue("Bar", "A", 1, null));
        byte[] before = oldCodec.encode(old);
        byte[] after = newCodec.encode(old);
        assertFalse(java.util.Arrays.equals(before, after),
                "inner enum schema evolution must change the shape digest");
        assertThrows(IllegalArgumentException.class, () -> oldCodec.decode(after));
        assertThrows(IllegalArgumentException.class, () -> newCodec.decode(before));
    }

    @Test
    void nestedWireRejectsNonAsciiNamesAnywhereInClosedDomain() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLS));
        var nested = registry.requireCase("Foo.Message1");
        var valid = new TaggedMessageContracts.Domain("Messages", List.of(nested));
        assertDoesNotThrow(() -> new TaggedMessageCodec(valid));

        assertThrows(IllegalArgumentException.class, () -> new TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Mässages", List.of(nested))));
        var owner = new TaggedMessageContracts.Case(
                "Föö", nested.name(), nested.tag(), nested.sourcePayload(), nested.payload());
        assertThrows(IllegalArgumentException.class, () -> new TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Messages", List.of(owner))));
        var caseName = new TaggedMessageContracts.Case(
                "Foo", "Mässage1", nested.tag(), nested.sourcePayload(), nested.payload());
        assertThrows(IllegalArgumentException.class, () -> new TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Messages", List.of(caseName))));

        // Mixing an otherwise-valid nested case with a non-ASCII
        // payloadless alternative must still fail the whole closed domain.
        var other = new TaggedMessageContracts.Case("Plain", "Évent", 1, null, null);
        assertThrows(IllegalArgumentException.class, () -> new TaggedMessageCodec(
                new TaggedMessageContracts.Domain("Messages", List.of(nested, other))));
    }

    @Test
    void primitiveBoxDeclarationIsLocalOnlyWithoutNestedEnumDependency() {
        var parsed = Parser.parse("""
                define enum Holder as
                    Empty
                    Boxed: Box<int>
                end
                """);
        var local = assertDoesNotThrow(() -> TaggedMessageContracts.forTypes(parsed));
        var boxed = local.requireCase("Holder.Boxed");
        assertEquals("Box", boxed.sourcePayload().name());
        assertNull(boxed.payload(), "Box<int> has no portable binary representation yet");
        assertThrows(IllegalArgumentException.class, () -> TaggedMessageContracts.verify(parsed));
        assertThrows(IllegalArgumentException.class,
                () -> new TaggedMessageCodec(local.requireDomain("Holder")));
    }

    @Test
    void boxIsExplicitIndirectionNotImplicitSerialization() {
        var source = Parser.parse("""
                define enum Foo as
                    Empty
                    Message1: Box<Foo>
                end
                """);
        assertEquals(2, TaggedMessageContracts.forTypes(source)
                .requireDomain("Foo").cases().size());
        assertThrows(IllegalArgumentException.class,
                () -> TaggedMessageContracts.verify(source));
    }
}

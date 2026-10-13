package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.TaggedMessageCodec;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TaggedMessageContractTest {
    private static final String DECLARATIONS = """
            define enum Status as
                Message1: Tuple[bool, string] = 1
                Message2: string = 2
                Message3: FixedArray[string, string, string] = 3
                Ready = 4
            end

            define pattern Messages as
                Status.Message1
                Status.Message2
                Status.Message3
            end
            """;

    @Test
    void parserPreservesTagsShapesAndImplicitNewlineAlternatives() {
        Ast.Program program = Parser.parse(DECLARATIONS);
        var registry = TaggedMessageContracts.verify(program);
        assertEquals(List.of(1, 2, 3, 4),
                registry.enums().get("Status").cases().stream()
                        .map(TaggedMessageContracts.Case::tag).toList());
        assertEquals(3, registry.patterns().get("Messages").cases().size());
        assertEquals("Status.Message2",
                registry.patterns().get("Messages").cases().get(1).qualifiedName());
    }

    @Test
    void defaultEnumTagsIncrementAndExplicitTagsCanReserveGaps() {
        var program = Parser.parse("""
                define enum Codes as
                    First
                    Next
                    Third = 8
                    Fourth
                end
                """);
        assertEquals(List.of(1, 2, 8, 9),
                TaggedMessageContracts.verify(program).enums().get("Codes").cases()
                        .stream().map(TaggedMessageContracts.Case::tag).toList());
    }

    @Test
    void rejectsPipeUnknownCaseDuplicateTagsAndNonSerializablePayload() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define pattern Bad as
                    Status.Message1 | Status.Message2
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Bad as
                    A = 1
                    B = 1
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define enum Bad as
                    Newer = 10
                    Older = 9
                end
                """));
        assertThrows(IllegalArgumentException.class, () -> TaggedMessageContracts.verify(Parser.parse(
                DECLARATIONS + """
                    define pattern Other as
                        Status.Missing
                    end
                    """)));
        assertThrows(IllegalArgumentException.class, () -> TaggedMessageContracts.verify(Parser.parse("""
                define enum Bad as
                    Foo: ActorId = 1
                end
                """)));
    }

    @Test
    void closedMessagePackDecoderPreservesNominalIdentityAndRejectsWrongCase() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLARATIONS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Messages"));
        var original = new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello");
        byte[] encoded = codec.encode(original);
        assertEquals("9402d98f6f7265736c616e672e656e756d2e363461373862626130343533393535323739306336633537666561306630353337346338373864623836313134393130356261366631373332633837326539342e34626264346332316663303037643839636136386637663061396136313438666162313266376539313361353931636633626131383033333061353931383634c420085917a950bf1470f59d654358e5da6f1744d6eadd4c4128f334ddbb6e032ddb93a653746174757302a568656c6c6f",
                java.util.HexFormat.of().formatHex(encoded),
                "Java and native must produce byte-identical tagged MessagePack v2");
        assertEquals(original, codec.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Status", "Ready", 4, null)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new TaggedMessageCodec.TaggedValue("Status", "Message2", 3, "hello")));
        var narrower = new TaggedMessageCodec(new TaggedMessageContracts.Domain(
                "OnlyFirst", List.of(registry.requireCase("Status.Message1"))));
        assertThrows(IllegalArgumentException.class, () -> narrower.decode(encoded));
        // A shape digest is not a message authentication code: changing
        // one valid string to another valid string is allowed. Instead,
        // tamper the type-binding digest, which must always be rejected.
        byte[] alteredDigest = encoded.clone();
        boolean found = false;
        for (int i = 0; i < alteredDigest.length - 34; i++) {
            if ((alteredDigest[i] & 255) == 0xc4
                    && (alteredDigest[i + 1] & 255) == 32) {
                alteredDigest[i + 2] ^= 1;
                found = true;
                break;
            }
        }
        assertTrue(found);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(alteredDigest));
    }

    @Test
    void decoderSelectsOnlyCompilerBoundSchemaFromBoundedCanonicalHeader() {
        var registry = TaggedMessageContracts.verify(Parser.parse(DECLARATIONS));
        var codec = new TaggedMessageCodec(registry.requireDomain("Messages"));
        var original = new TaggedMessageCodec.TaggedValue("Status", "Message2", 2, "hello");
        var bytes = codec.encode(original);
        String schemaId = dev.oreslang.runtime.MessagePackCollectionCodec
                .envelopeSchemaId(bytes);
        assertTrue(schemaId.startsWith("oreslang.enum."));
        assertEquals(original, codec.decode(bytes));

        // Flip one *ASCII schema byte*, keeping the canonical string frame
        // valid. An unknown wire ID must fail immediately against the
        // compiled map rather than being tried against every codec case.
        byte[] forged = bytes.clone();
        byte[] id = schemaId.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        boolean found = false;
        for (int offset = 0; offset <= forged.length - id.length; offset++) {
            boolean equal = true;
            for (int i = 0; i < id.length; i++) {
                if (forged[offset + i] != id[i]) { equal = false; break; }
            }
            if (equal) {
                forged[offset] = forged[offset] == 'o' ? (byte)'p' : (byte)'o';
                found = true;
                break;
            }
        }
        assertTrue(found, "canonical schema ID present in wire envelope");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(forged));

        byte[] wrongVersion = bytes.clone();
        wrongVersion[1] = 3;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(
                new byte[dev.oreslang.runtime.MessagePackCollectionCodec.MAX_BYTES + 1]));
    }

    @Test
    void typecheckerAcceptsCheckedVariantConstructionAndRejectsOtherDomainCases() {
        var valid = DECLARATIONS + """
                define actor Worker as
                    receive(ActorMail<Messages> mail): void {
                        self.end();
                        return;
                    }
                end

                pub async routine main(): void {
                    val a = await spawn Worker();
                    a.send("hello" as Status.Message2);
                    return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(valid)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "a.send(\"hello\" as Status.Message2);",
                        "a.send(Status.Ready());"))));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(valid.replace(
                        "a.send(\"hello\" as Status.Message2);",
                        "a.send(7 as Status.Message2);"))));
    }
}

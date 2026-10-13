package dev.oreslang;

import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.runtime.MessagePackCollectionCodec.Shape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static dev.oreslang.runtime.MessagePackCollectionCodec.*;
import static org.junit.jupiter.api.Assertions.*;

final class MessagePackCollectionCodecTest {
    private static void vector(String schema, Shape shape, Object input, String expectedHex, Object expectedValue) {
        byte[] encoded = encode(schema, shape, input);
        assertArrayEquals(HexFormat.of().parseHex(expectedHex), encoded, "MessagePack golden vector");
        assertEquals(expectedValue, decode(schema, shape, encoded), "schema-guided decode");
        assertArrayEquals(encoded, encode(schema, shape, expectedValue), "canonical re-encode");
    }

    @Test
    void pythonMessagePackCompatibleGoldenVectorsDistinguishContainerSchemas() {
        vector("demo.TupleBoolString.v1", tuple(bool(), string()), List.of(true, "foo"),
                "9402b764656d6f2e5475706c65426f6f6c537472696e672e7631c420d254b4ecd3d7964454dcdf86fd10ede585a17b3af27982a289819afdc2379ab292c3a3666f6f",
                List.of(true, "foo"));
        vector("demo.FixedArrayInts.v1", fixedArray(int64(), int64(), int64()),
                new long[]{1, 2, 3},
                "9402b664656d6f2e46697865644172726179496e74732e7631c4206a46f6c65c619cc1c5354f7bd3b35ec0fcfbc9866c0760a15b860cfef79773b993010203",
                List.of(1L, 2L, 3L));
        vector("demo.FixedListNested.v1", fixedList(list(string()), bool()),
                List.of(List.of("a", "b"), false),
                "9402b764656d6f2e46697865644c6973744e65737465642e7631c4202007a0279d88d3eca7d21cf32b37327b996593c1353963dd78555f56f1ba6ddc9292a161a162c2",
                List.of(List.of("a", "b"), false));
        vector("demo.ArrayStrings.v1", array(string()), List.of("a", "b"),
                "9402b464656d6f2e4172726179537472696e67732e7631c420139722a10b9ef4d4af0cea1bfbc50ff92a0459aabf837f63a6fec052fe5dc5c992a161a162",
                List.of("a", "b"));
        vector("demo.ListStrings.v1", list(string()), List.of("a", "b"),
                "9402b364656d6f2e4c697374537472696e67732e7631c4204efbea59591d5c31d9f13ee93ef5c1d954e653144d380bb2d064ea4fa6d56b8c92a161a162",
                List.of("a", "b"));
        vector("demo.String.v1", string(), "foo",
                "9402ae64656d6f2e537472696e672e7631c42021c2efdbff08dd4e1320d8abe1ba0cc6aea8e4cf104b984be7b7079968fad3cda3666f6f", "foo");
    }

    @Test
    void rejectsWrongSchemaMalformedInputAndInvalidUTF8() {
        Shape shape = tuple(bool(), string());
        byte[] bytes = encode("demo.TupleBoolString.v1", shape, List.of(true, "foo"));
        assertThrows(IllegalArgumentException.class,
                () -> decode("other.Schema.v1", shape, bytes));
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", fixedArray(string(), string()), bytes));
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", shape, Arrays.copyOf(bytes, bytes.length - 1)));
        byte[] extra = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.TupleBoolString.v1", shape, extra));

        byte[] malformed = encode("demo.String.v1", string(), "foo");
        malformed[malformed.length - 3] = (byte) 0xc0;
        malformed[malformed.length - 2] = (byte) 0xaf;
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.String.v1", string(), malformed));
    }

    @Test
    void rejectsUnsafeObjectGraphsAndInvalidFixedArity() {
        Shape typed = fixedArray(int64(), string());
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1, new Object())));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.FixedArray.v1", typed, List.of(1, "ok", 3)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.Bad.v1", string(), new Object()));

        ArrayList<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.Cyclic.v1", list(list(string())), cyclic));
    }

    @Test
    void schemaIdentityAloneCannotSubstituteCollectionKindOrElementShape() {
        String id = "domain.with.overlapping.CollectionSchema.v1";
        byte[] listWire = encode(id, list(string()), List.of("abc"));
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, array(string()), listWire));
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, tuple(string()), listWire));
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, list(int64()), listWire));

        byte[] nested = encode(id, tuple(list(string()), bool()),
                List.of(List.of("abc"), true));
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, tuple(array(string()), bool()), nested));

        byte[] forgedDigest = listWire.clone();
        int digest = -1;
        for (int i = 0; i < forgedDigest.length - 34; i++) {
            if ((forgedDigest[i] & 255) == 0xc4
                    && (forgedDigest[i + 1] & 255) == 32) {
                digest = i + 2;
                break;
            }
        }
        assertTrue(digest > 0, "wire v2 must carry a bin32 shape hash");
        forgedDigest[digest] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, list(string()), forgedDigest));
    }

    @Test
    void rejectsLegacyFramesAndNonCanonicalAlternativeEncodings() {
        String id = "test.Minimal.i64";
        byte[] canonicalInteger = encode(id, int64(), 1);
        assertEquals(2, canonicalInteger[1]);
        byte[] legacyVersion = canonicalInteger.clone();
        legacyVersion[1] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, int64(), legacyVersion));

        byte[] nonminimalInteger = Arrays.copyOf(canonicalInteger, canonicalInteger.length + 1);
        nonminimalInteger[nonminimalInteger.length - 2] = (byte) 0xcc;
        nonminimalInteger[nonminimalInteger.length - 1] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, int64(), nonminimalInteger));

        byte[] validString = encode(id, string(), "a");
        byte[] nonminimalString = Arrays.copyOf(validString, validString.length + 1);
        nonminimalString[nonminimalString.length - 3] = (byte) 0xd9;
        nonminimalString[nonminimalString.length - 2] = 1;
        nonminimalString[nonminimalString.length - 1] = 'a';
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, string(), nonminimalString));

        byte[] nonminimalEnvelope = Arrays.copyOf(canonicalInteger,
                canonicalInteger.length + 2);
        System.arraycopy(canonicalInteger, 1, nonminimalEnvelope, 3,
                canonicalInteger.length - 1);
        nonminimalEnvelope[0] = (byte) 0xdc;
        nonminimalEnvelope[1] = 0;
        nonminimalEnvelope[2] = 4;
        assertThrows(IllegalArgumentException.class,
                () -> decode(id, int64(), nonminimalEnvelope));
    }

    @Test
    void malformedArrayLengthFailsBeforeLargeDecoderAllocation() {
        String id = "test.MalformedLength";
        byte[] empty = encode(id, list(string()), List.of());
        byte[] forged = Arrays.copyOf(empty, empty.length + 2);
        forged[forged.length - 3] = (byte) 0xdc;
        forged[forged.length - 2] = 0;
        forged[forged.length - 1] = 100;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> decode(id, list(string()), forged));
        assertTrue(error.getMessage().contains("remaining input/node budget"),
                error.getMessage());
    }

    @Test
    void nestedEnumWireNamesCannotCollideThroughLossyAsciiEncoding() {
        var empty = new MessagePackCollectionCodec.EnumCaseShape("Message1", 1, null);
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackCollectionCodec.NestedEnum("Bár", List.of(empty)));
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackCollectionCodec.NestedEnum("B?r", List.of(empty)));
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackCollectionCodec.NestedEnum("B" + "x".repeat(256),
                        List.of(empty)));
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackCollectionCodec.EnumCaseShape("Mássage1", 1, null));
        assertThrows(IllegalArgumentException.class, () ->
                new MessagePackCollectionCodec.EnumCaseShape("M?ssage1", 1, null));

        var valid = new MessagePackCollectionCodec.NestedEnum("Bar", List.of(empty));
        assertNotNull(MessagePackCollectionCodec.shapeDigest(valid));
    }

    @Test
    void nestedEnumDescriptorBudgetRejectsOversizedClosedCaseCatalog() {
        var names = new java.util.ArrayList<MessagePackCollectionCodec.EnumCaseShape>();
        for (int i = 1; i <= 4096; i++) {
            names.add(new MessagePackCollectionCodec.EnumCaseShape(
                    "C" + "z".repeat(243) + i, i, null));
        }
        var shape = new MessagePackCollectionCodec.NestedEnum("Bar", names);
        var problem = assertThrows(IllegalArgumentException.class,
                () -> MessagePackCollectionCodec.shapeDigest(shape));
        assertTrue(problem.getMessage().contains("descriptor exceeds byte quota"),
                problem.getMessage());
    }

    @Test
    void decoderEnforcesBudgetsAndNumericalTypes() {
        assertThrows(IllegalArgumentException.class,
                () -> decode("demo.X.v1", list(string()), new byte[MAX_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", string(), "x".repeat(MAX_BYTES + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", float64(), Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", float64(), Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> encode("demo.X.v1", int64(), 1.25));
        assertEquals(-128L, decode("demo.I.v1", int64(), encode("demo.I.v1", int64(), -128L)));
        assertEquals(Long.MAX_VALUE,
                decode("demo.I.v1", int64(), encode("demo.I.v1", int64(), Long.MAX_VALUE)));
    }
}

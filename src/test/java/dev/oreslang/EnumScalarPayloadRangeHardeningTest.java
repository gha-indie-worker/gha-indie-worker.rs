package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalEnumValueGuards;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Local nominal enum value types must not inherit wire-type erasure. */
final class EnumScalarPayloadRangeHardeningTest {
    private static TaggedMessageContracts.Case scalar(String spelling) {
        var registry = TaggedMessageContracts.forTypes(Parser.parse("""
                define enum Value as
                    Payload: %s
                end
                """.formatted(spelling)));
        return registry.requireCase("Value.Payload");
    }

    private static TaggedMessageContracts.Registry registry(String spelling) {
        return TaggedMessageContracts.forTypes(Parser.parse("""
                define enum Value as
                    Payload: %s
                end
                """.formatted(spelling)));
    }

    private static void passes(String spelling, Object payload) {
        var types = registry(spelling);
        assertDoesNotThrow(() -> NominalEnumValueGuards.requirePayload(
                types, types.requireCase("Value.Payload"), payload));
    }

    private static void rejects(String spelling, Object payload) {
        var types = registry(spelling);
        assertThrows(IllegalArgumentException.class,
                () -> NominalEnumValueGuards.requirePayload(
                        types, types.requireCase("Value.Payload"), payload));
    }

    @Test
    void localUnsignedByteIsNominallyValidButNotWireSerializable() {
        String source = """
                define enum Status as
                    Loading
                    Active: u8
                    Failed
                end
                fnc extract(Status status): int {
                    match status over
                        on Status.Loading -> { return 0; }
                        on Status.Active(value) -> { return value; }
                        on Status.Failed -> { return -1; }
                    end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));

        var types = registry("u8");
        assertNull(types.requireCase("Value.Payload").payload(),
                "local-only width must never acquire a fake portable wire codec");
        assertThrows(IllegalArgumentException.class, () ->
                TaggedMessageContracts.verify(Parser.parse("""
                        define enum Value as
                            Payload: u8
                        end
                        """)));

        passes("u8", 0L);
        passes("u8", 255L);
        rejects("u8", -1L);
        rejects("u8", 256L);
        rejects("u8", "42");
    }

    @Test
    void signedAndUnsignedRangesAreCheckedBeforeEnumConstruction() {
        passes("i8", -128L);
        passes("i8", 127L);
        rejects("i8", -129L);
        rejects("i8", 128L);
        passes("u16", 65535L);
        rejects("u16", 65536L);
        rejects("u16", -1L);
        passes("u64", BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE));
        rejects("u64", BigInteger.ONE.shiftLeft(64));
        rejects("u64", -1L);
        passes("bigint", BigInteger.ONE.shiftLeft(128));
    }

    @Test
    void runtimeCannotTrustForgedCaseDescriptorsEvenWithValidPayload() {
        var types = registry("u8");
        var declared = types.requireCase("Value.Payload");
        var forgedTag = new TaggedMessageContracts.Case(
                declared.owner(), declared.name(), declared.tag() + 1,
                declared.sourcePayload(), declared.payload());
        var forgedType = new TaggedMessageContracts.Case(
                declared.owner(), declared.name(), declared.tag(),
                dev.oreslang.ast.Ast.TypeRef.simple("string"), declared.payload());
        var forgedShape = new TaggedMessageContracts.Case(
                declared.owner(), declared.name(), declared.tag(),
                declared.sourcePayload(),
                dev.oreslang.runtime.MessagePackCollectionCodec.string());
        assertDoesNotThrow(() ->
                NominalEnumValueGuards.requirePayload(types, declared, 17L));
        for (var forged : List.of(forgedTag, forgedType, forgedShape)) {
            assertThrows(IllegalArgumentException.class,
                    () -> NominalEnumValueGuards.requirePayload(types, forged, 17L));
        }
    }

    @Test
    void scalarRuntimeGuardsRejectHostForgedValues() {
        passes("bool", true);
        rejects("bool", 1L);
        passes("string", "ready");
        rejects("string", 17L);
        passes("int", Long.MAX_VALUE);
        rejects("int", BigInteger.ONE.shiftLeft(63));
        rejects("int", 4.5);
        passes("f64", 2.5);
        rejects("f64", Double.NaN);
        rejects("f64", Double.POSITIVE_INFINITY);
        rejects("f64", 42L);
    }

    @Test
    void nestedProductFieldsAreAllValidated() {
        passes("Tuple[bool, u8]", List.of(true, 255L));
        rejects("Tuple[bool, u8]", List.of(true, 256L));
        rejects("Tuple[bool, u8]", List.of("true", 1L));
        rejects("Tuple[bool, u8]", List.of(true));
        passes("List<u8>", List.of(0L, 255L));
        rejects("List<u8>", List.of(0L, 256L));
    }

    @Test
    void outOfRangeConstantConstructorFailsAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                end
                fnc tooLarge(): Status {
                    return Status.Active(256);
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                end
                fnc valid(): Status {
                    return Status.Active(255);
                }
                """)));
    }

    @Test
    void enumCastRejectsImpossibleWidthButAcceptsLegalValue() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                end
                fnc invalid(): Status {
                    return 256 as Status.Active;
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                end
                fnc valid(): Status {
                    return 255 as Status.Active;
                }
                """)));
    }

    @Test
    void numericLiteralCasePatternsCannotRepresentOutOfRangeValues() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                    Idle
                end
                fnc impossible(Status value): int {
                    match value over
                        on Status.Active(256) -> { return 1; }
                        on Status.Active(_) -> { return 2; }
                        on Status.Idle -> { return 0; }
                    end
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Pair: Tuple[bool, u8]
                    Idle
                end
                fnc impossible(Status value): int {
                    match value over
                        on Status.Pair as (true, 256) -> { return 1; }
                        on Status.Pair as (_, _) -> { return 2; }
                        on Status.Idle -> { return 0; }
                    end
                }
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    Active: u8
                    Idle
                end
                fnc legal(Status value): int {
                    match value over
                        on Status.Active(255) -> { return 1; }
                        on Status.Active(_) -> { return 2; }
                        on Status.Idle -> { return 0; }
                    end
                }
                """)));
    }

    @Test
    void enumAndPatternNamesCannotShadowExistingTypeNamespaces() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Option as
                    A
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    A
                end
                define class Status as
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define match pattern List as
                    ...Status
                end
                define enum Status as
                    A
                end
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define enum Status as
                    A
                end
                fnc erroneous(Status<int> value): void {
                    return;
                }
                """)));
    }

    @Test
    void unsupportedLocalPayloadsAndUncompiledWireNeverBecomeAccepted() {
        for (String payloadType : List.of("Channel<int>", "ActorRef<Worker>",
                "UnknownType", "Option<u8>", "Result<bool, string>", "f32")) {
            assertThrows(IllegalArgumentException.class,
                    () -> registry(payloadType), payloadType);
        }
        assertThrows(IllegalArgumentException.class, () ->
                NominalEnumValueGuards.requirePayload(registry("u8"),
                        scalar("u8"), new Object()));
    }
}

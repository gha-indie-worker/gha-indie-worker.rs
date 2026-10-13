package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.MessagePackCollectionCodec;
import dev.oreslang.types.ActorWireSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ActorWireSchemaTest {
    private static Ast.TypeRef t(String name, Ast.TypeRef... parts) {
        return new Ast.TypeRef(name, List.of(parts), false);
    }

    @Test
    void arrayAndListHaveDistinctWireFingerprintsDespiteCommonTypeCheckerListType() {
        var array = ActorWireSchema.fromSource(t("Array", t("string")));
        var list = ActorWireSchema.fromSource(t("List", t("string")));
        assertNotEquals(array, list);
        assertFalse(java.util.Arrays.equals(
                MessagePackCollectionCodec.shapeDigest(array),
                MessagePackCollectionCodec.shapeDigest(list)));
    }

    @Test
    void fixedContainerKindArityAndNestedTypesArePreserved() {
        var shapes = List.of(t("bool"), t("string"));
        var tuple = ActorWireSchema.fromSource(
                t("Tuple", Ast.TypeRef.sequenceShape(shapes)));
        var fixedArray = ActorWireSchema.fromSource(
                t("FixedArray", Ast.TypeRef.sequenceShape(shapes)));
        var fixedList = ActorWireSchema.fromSource(
                t("FixedList", Ast.TypeRef.sequenceShape(shapes)));
        assertNotEquals(tuple, fixedArray);
        assertNotEquals(tuple, fixedList);
        assertNotEquals(fixedArray, fixedList);
        var nested = ActorWireSchema.fromSource(t("List",
                t("Tuple", Ast.TypeRef.sequenceShape(shapes))));
        assertEquals(
                MessagePackCollectionCodec.list(MessagePackCollectionCodec.tuple(
                        MessagePackCollectionCodec.bool(),
                        MessagePackCollectionCodec.string())),
                nested);
    }

    @Test
    void rejectsUnportableNumericWidthAndAmbiguousSourceTypes() {
        for (String type : List.of("u8", "u64", "i32", "bigint", "f32",
                "decimal", "complex", "Vector", "Slice", "Future", "ActorId")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ActorWireSchema.fromSource(t(type)), type);
        }
    }

    @Test
    void resolvesAlreadyImportedMonomorphicAliasesAndRejectsCycles() {
        var message = t("Array", t("Word"));
        var schema = ActorWireSchema.fromSource(message, Map.of("Word", t("string")));
        assertEquals(MessagePackCollectionCodec.array(MessagePackCollectionCodec.string()), schema);
        assertThrows(IllegalArgumentException.class,
                () -> ActorWireSchema.fromSource(t("A"),
                        Map.of("A", t("B"), "B", t("A"))));
    }
}

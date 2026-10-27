package dev.oreslang.types;

import dev.oreslang.ast.TypedOverloadResolver.TypeKey;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.Map;
import java.util.Objects;

/**
 * Conservative bridge from fully typechecked Oreslang types to future typed
 * overload identities. No structural, borrowed, erased generic, null, or
 * dynamic payload distinctions are silently promoted into runtime dispatch.
 *
 * <p>The nominal enum registry is supplied by TypeChecker after lexical
 * resolution and must contain ONLY concrete enum declarations. Interface and
 * class names with the same short spelling must never be present in it.
 */
public final class CheckedOverloadTypes {
    private CheckedOverloadTypes() { }

    public static TypeKey discriminator(
            Type resolved, Map<String, String> enumIdentities) {
        Objects.requireNonNull(resolved, "resolved type");
        Objects.requireNonNull(enumIdentities, "enum identity registry");
        if (resolved == Unknown.INSTANCE) return TypeKey.unknown();
        if (resolved instanceof StringLiteral) {
            // Literal *content* must never become an overload discriminator.
            return TypeKey.primitive("String");
        }
        if (resolved instanceof Primitive primitive) {
            return switch (primitive) {
                case INT -> TypeKey.primitive("int");
                case FLOAT -> TypeKey.primitive("float");
                case DECIMAL -> TypeKey.primitive("decimal");
                case COMPLEX -> TypeKey.primitive("complex");
                case BOOL -> TypeKey.primitive("bool");
                case STRING -> TypeKey.primitive("String");
                case NULL, VOID -> throw new IllegalArgumentException(
                        "null/void cannot distinguish a typed overload");
            };
        }
        if (resolved instanceof Named named) {
            if (!named.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "unreified generic payload cannot distinguish an overload: " + named);
            }
            String identity = enumIdentities.get(named.name());
            if (identity == null) {
                throw new IllegalArgumentException(
                        "not a resolved nominal enum type: " + named.name());
            }
            return TypeKey.nominalEnum(identity);
        }
        if (resolved instanceof Borrow || resolved instanceof Generic) {
            throw new IllegalArgumentException(
                    "borrowed/generic type cannot be a runtime overload discriminator: " + resolved);
        }
        // SelfType, structural Record, Tuple, ListType, Function, Union,
        // ClassNamespace and all other type families fail closed. A future
        // union/dynamic overload design requires a separate proof of
        // totality and unique static dispatch.
        throw new IllegalArgumentException(
                "type cannot distinguish a runtime overload: " + resolved);
    }
}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static Serializable actor-boundary admission. These tests cover the closed
 * built-in schema subset only. User-authored @Serializable codec derivation,
 * enum/pattern domains and portable wire encoding are separate pending work.
 */
final class ActorMessageSerializableContractTest {
    @Test
    void acceptsPrimitiveStringAndRecursiveBuiltInCollectionMessages() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("foo");
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<List<string>> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send(["foo", "bar"]);
                  return;
                }
                """)));
    }

    @Test
    void tuplesFixedArraysFixedListsAndNestedVariableCollectionsAreSerializable() {
        String[] validPayloadTypes = {
                "Tuple[bool, string]",
                "FixedArray[bool, string]",
                "FixedList[bool, string]",
                "Array[string]",
                "List[string]",
                "Array[List[string]]",
                "List[Tuple[bool, string]]"
        };
        for (String payloadType : validPayloadTypes) {
            String source = """
                    define actor Worker as
                      receive(ActorMail<%s> mail): void { return; }
                    end
                    """.formatted(payloadType);
            assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)), payloadType);
        }
    }

    @Test
    void nestedNonSerializableTypeIsRejectedInsideEveryCollectionKind() {
        String[] invalidPayloadTypes = {
                "Tuple[bool, ActorId]",
                "FixedArray[bool, ActorId]",
                "FixedList[bool, ActorId]",
                "Array[ActorId]",
                "List[ActorId]",
                "List[Tuple[bool, ActorId]]"
        };
        for (String payloadType : invalidPayloadTypes) {
            String source = """
                    define actor Worker as
                      receive(ActorMail<%s> mail): void { return; }
                    end
                    """.formatted(payloadType);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), payloadType);
            assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
        }
    }

    @Test
    void rejectsAnyTypeWithoutAnImplementedPortableCodec() {
        String[] notYetPortable = {
                "decimal",
                "complex",
                "Option<string>",
                "Result<string, int>",
                "string | int"
        };
        for (String candidate : notYetPortable) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Worker as
                              receive(ActorMail<%s> mail): void { return; }
                            end
                            """.formatted(candidate))), candidate);
            assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
        }
    }

    @Test
    void rejectsNumericWidthErasureAndOpaqueCollectionKindAtMailbox() {
        String[] unsupported = {
                "i32", "u64", "bigint", "f32", "Vector[string]",
                "Slice[string]", "List[u16]", "Tuple[bool, uint]"
        };
        for (String type : unsupported) {
            String source = """
                    define actor Worker as
                      receive(ActorMail<%s> mail): void { return; }
                    end
                    """.formatted(type);
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), type);
            assertTrue(failure.getMessage().contains("Serializable")
                            || failure.getMessage().contains("actor-boundary"),
                    failure.getMessage());
        }

        String aliasSource = """
                type BigCount = bigint;
                define actor Worker as
                  receive(ActorMail<List<BigCount>> mail): void { return; }
                end
                """;
        IllegalArgumentException aliasedFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(aliasSource)));
        // This same-file alias currently fails an earlier actor lexical
        // isolation gate, so it is NOT evidence of imported-alias serialization
        // checking. Imported type alias coverage is a separate integration gate.
        assertTrue(aliasedFailure.getMessage().contains("same-file type alias"),
                aliasedFailure.getMessage());
    }

    @Test
    void rejectsRuntimeCapabilityPayloadEvenWhenFormerlySendable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<ActorId> mail): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
    }

    @Test
    void rejectsCapabilitiesNestedInsideCollectionPayload() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<List<ActorId>> mail): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
    }

    @Test
    void rejectsClassInstancesWithoutGeneratedPortableCodecs() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Payload as
                          value: string
                        end
                        define actor Worker as
                          receive(ActorMail<Payload> mail): void { return; }
                        end
                        """)));
        // Existing actor lexical isolation rules may reject a same-file class
        // even earlier than the required serialization gate.
        assertTrue(failure.getMessage().contains("Serializable")
                        || failure.getMessage().contains("actor")
                        || failure.getMessage().contains("data-only"),
                failure.getMessage());
    }

    @Test
    void serializationAnnotationDoesNotSilentlyPretendToGenerateCodec() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("""
                        @Serializable
                        define class Message as
                          value: string
                        end
                        """));
        assertTrue(failure.getMessage().contains("codec derivation"), failure.getMessage());
    }

    @Test
    void rejectsUnknownOpaqueOrExecutablePayloadTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<Fnc<void>> mail): void { return; }
                end
                """)));
    }
}

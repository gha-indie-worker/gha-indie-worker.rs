package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The ownership pass must see the same nominal enum payload shapes as typing. */
final class EnumOwnershipMatchTest {
    @Test
    void primitiveEnumPayloadIsCopyInsteadOfAnUnknownMoveOnlyAlias() {
        String source = """
                define enum Signal as
                    Active: u8
                    Idle
                end
                fnc classify(Signal input): int {
                    match input over
                        on Signal.Active(value) -> { return value + value; }
                        on Signal.Idle -> { return 0; }
                    end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nestedProductProjectionsRetainConcreteFieldTypes() {
        String source = """
                define enum Packet as
                    Pair: Tuple[bool, u8]
                    Empty
                end
                fnc classify(Packet packet): int {
                    match packet over
                        on Packet.Pair as (ok, count) -> {
                            if ok then
                                return count + count;
                            fi
                            return 0;
                        }
                        on Packet.Empty -> { return 0; }
                    end
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void nestedNominalPayloadCannotBeMovedTwice() {
        String source = """
                define enum Inner as
                    Ready
                end
                define enum Outer as
                    Wrapped: Inner
                end
                fnc consume(Inner value): void { return; }
                fnc misuse(Outer wrapped): void {
                    match wrapped over
                        on Outer.Wrapped(inner) -> {
                            consume(inner);
                            consume(inner);
                            return;
                        }
                    end
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("moved"), failure.toString());
    }

    @Test
    void ownershipPassIndependentlyRejectsForeignEnumPattern() {
        String source = """
                define enum A as
                    Ready
                end
                define enum B as
                    Ready
                end
                fnc classify(A value): int {
                    match value over
                        on B.Ready -> { return 1; }
                        on _ -> { return 0; }
                    end
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }
}

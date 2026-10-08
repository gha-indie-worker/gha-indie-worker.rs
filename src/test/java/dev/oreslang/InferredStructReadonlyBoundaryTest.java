package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InferredStructReadonlyBoundaryTest {
    @Test void mutableDestructureAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val [item] = [infer struct{foo: "a"}];
                  return;
                }
                """)));
    }

    @Test void mutableForOfAliasIsRejectedAtBinding() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  for val item of arr[infer struct{foo: "a"}] do
                    stdio.println(item.foo);
                  done
                  return;
                }
                """)));
    }

    @Test void mutableGenericParameterCannotReceiveReadonlyInferredRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc touch<T>(T mut value): void {
                  return;
                }
                fnc bad(): void {
                  touch(infer struct{foo: "a"});
                  return;
                }
                """)));
    }
    @Test void structuralCastCannotUpgradeReadonlyRecord() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = infer struct{foo: "a"};
                  let mut alias = source as {foo: string};
                  alias.foo = "b";
                  return;
                }
                """)));
    }

    @Test void returnCannotEraseReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc leak(): {foo: string} {
                  return infer struct{foo: "a"};
                }
                """)));
    }

    @Test void fixedStructuralParameterCannotRoundTripReadonlyAsMutableShape() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity({foo: string} value): {foo: string} {
                  return value;
                }
                fnc bad(): void {
                  const result = identity(infer struct{foo: "a"});
                  return;
                }
                """)));
    }

    @Test void collectionInsertionCannotEraseNestedReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val items = arr[struct{foo: string}{foo: "mutable"}];
                  items.add(infer struct{foo: "readonly"});
                  return;
                }
                """)));
    }

    @Test void genericIdentityPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc identity<T>(T value): T {
                  return value;
                }
                fnc bad(): void {
                  let mut upgraded = identity(infer struct{foo: "a"});
                  upgraded.foo = "b";
                  return;
                }
                """)));
    }

    @Test void structuralBorrowViewMayReadReadonlyInferredRecord() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc read(structural {foo: string} value): string {
                  return value.foo;
                }
                fnc ok(): string {
                  return read(infer struct{foo: "a"});
                }
                """)));
    }

    @Test void typeTestBindingPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = infer struct{foo: "a"};
                  if source is type {foo: string} narrowed then
                    let mut alias = narrowed;
                    alias.foo = "b";
                  fi
                  return;
                }
                """)));
    }

    @Test void typedMatchBindingPreservesReadonlyCapability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  const source = infer struct{foo: "a"};
                  match source
                    is {foo: string} narrowed -> {
                      let mut alias = narrowed;
                      alias.foo = "b";
                      return;
                    }
                    else -> { return; }
                  end
                }
                """)));
    }

    @Test void ownershipTypeTestBindingPreservesReadonlyCapability() {
        var program = Parser.parse("""
                fnc bad(): void {
                  const source = infer struct{foo: "a"};
                  if source is type {foo: string} narrowed then
                    let mut alias = narrowed;
                    return;
                  fi
                  return;
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

    @Test void ownershipTypedMatchBindingPreservesReadonlyCapability() {
        var program = Parser.parse("""
                fnc bad(): void {
                  const source = infer struct{foo: "a"};
                  match source
                    is {foo: string} narrowed -> {
                      let mut alias = narrowed;
                      return;
                    }
                    else -> { return; }
                  end
                }
                """);

        assertThrows(IllegalArgumentException.class, () -> OwnershipChecker.check(program));
    }

}

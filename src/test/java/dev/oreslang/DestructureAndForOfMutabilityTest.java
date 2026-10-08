package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class DestructureAndForOfMutabilityTest {
    private static void accepts(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test void prefixedDestructurePreservesAllFourModes() {
        accepts("""
                fnc ok(): void {
                  val [item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  val [item] = [struct{foo: string}{foo: "a"}];
                  item = struct{foo: string}{foo: "b"};
                  return;
                }
                """);

        accepts("""
                fnc ok(): void {
                  let [item] = [struct{foo: string}{foo: "a"}];
                  item = struct{foo: string}{foo: "b"};
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  let [item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  return;
                }
                """);

        accepts("""
                fnc ok(): void {
                  let mut [item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  item = struct{foo: string}{foo: "c"};
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  const [item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  return;
                }
                """);
    }

    @Test void inlineDestructureKindsPreserveReferentPermission() {
        accepts("""
                fnc ok(): void {
                  [val item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  return;
                }
                """);
        accepts("""
                fnc ok(): void {
                  [let mut item] = [struct{foo: string}{foo: "a"}];
                  item.foo = "b";
                  item = struct{foo: string}{foo: "c"};
                  return;
                }
                """);
    }

    @Test void forOfValIsFixedMutableAndLetIsRebindableReadonly() {
        accepts("""
                fnc ok(): void {
                  for val item of arr[struct{foo: string}{foo: "a"}] do
                    item.foo = "b";
                  done
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  for val item of arr[struct{foo: string}{foo: "a"}] do
                    item = struct{foo: string}{foo: "b"};
                  done
                  return;
                }
                """);

        accepts("""
                fnc ok(): void {
                  for let item of arr[struct{foo: string}{foo: "a"}] do
                    item = struct{foo: string}{foo: "b"};
                  done
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  for let item of arr[struct{foo: string}{foo: "a"}] do
                    item.foo = "b";
                  done
                  return;
                }
                """);

        accepts("""
                fnc ok(): void {
                  for let mut item of arr[struct{foo: string}{foo: "a"}] do
                    item.foo = "b";
                    item = struct{foo: string}{foo: "c"};
                  done
                  return;
                }
                """);
    }

    @Test void forOfDestructureCarriesInheritedMutability() {
        accepts("""
                fnc ok(): void {
                  for val [item] of arr[[struct{foo: string}{foo: "a"}]] do
                    item.foo = "b";
                  done
                  return;
                }
                """);
        accepts("""
                fnc ok(): void {
                  for let mut [item] of arr[[struct{foo: string}{foo: "a"}]] do
                    item.foo = "b";
                    item = struct{foo: string}{foo: "c"};
                  done
                  return;
                }
                """);
        rejects("""
                fnc bad(): void {
                  for const [item] of arr[[struct{foo: string}{foo: "a"}]] do
                    item.foo = "b";
                  done
                  return;
                }
                """);
    }
}

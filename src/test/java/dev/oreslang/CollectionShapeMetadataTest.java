package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class CollectionShapeMetadataTest {
    @Test
    void lexerRecognizesSequenceEllipsisWithoutBreakingDot() {
        List<Token> tokens = new Lexer("Vector[...(int, string)] GP.Foo").scan();
        assertTrue(tokens.stream().anyMatch(token -> token.type() == Token.Type.ELLIPSIS));
        assertTrue(tokens.stream().anyMatch(token -> token.type() == Token.Type.DOT));
    }

    @Test
    void fixedArraySupportsCountedHeterogeneousFiniteShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val xs: FixedArray[3 of int, 3 of string] =
                      [10, 20, 30, "foo", "bar", "baz"];
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: FixedArray[3 of int, 3 of string] =
                              [10, 20, "foo", "bar", "baz"];
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("fixed sequence shape"));
    }

    @Test
    void vectorRequiresExplicitRepetitionForMultiElementSequencePattern() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector[...(int, string, bool)] =
                      [5, "foo", true, 6, "bar", false];
                  return;
                }
                """)));

        IllegalArgumentException finite = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: Vector[int, string, bool] = [5, "foo", true];
                          return;
                        }
                        """)));
        assertTrue(finite.getMessage().contains("finite sequence pattern"));

        IllegalArgumentException incomplete = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: Vector[...(int, string, bool)] = [5, "foo"];
                          return;
                        }
                        """)));
        assertTrue(incomplete.getMessage().contains("does not complete repeating sequence group"));
    }

    @Test
    void arbitraryUnionAndRepeatingPatternRemainDistinct() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val arbitrary: Vector[int | string | bool] = [true, 7, "x", false];
                  val patterned: Vector[...(int, string, bool)] =
                      [7, "x", true, 8, "y", false];
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val patterned: Vector[...(int, string, bool)] =
                              [true, 7, "x"];
                          return;
                        }
                        """)));
    }

    @Test
    void namedParamsAnnotationAndInlineMetadataCanonicalizeToSameTypeRef() {
        Ast.TypeRef inline = bindingType(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector<
                      allocator=Arena,
                      align=64,
                      growth_policy=GP.Foo
                  >[...(int, string, bool)] = [5, "foo", true];
                  return;
                }
                """), "xs");

        Ast.TypeRef annotated = bindingType(Parser.parse("""
                pub fnc main(): void {
                  @NamedParams<
                      growth_policy=GP.Foo,
                      allocator=Arena,
                      align=64
                  >
                  val xs: Vector[...(int, string, bool)] = [5, "foo", true];
                  return;
                }
                """), "xs");

        assertEquals(inline, annotated);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  @GrowthPolicy<GP.Foo>
                  @Allocator<Arena>
                  @Align<64>
                  val xs: Vector[...(int, string, bool)] = [5, "foo", true];
                  return;
                }
                """)));
    }

    @Test
    void namedMetadataIsOrderIndependentAndRejectsConflicts() {
        Ast.TypeRef left = bindingType(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector<align=64, allocator=Arena, growth_policy=GP.Foo>[int] = [1];
                  return;
                }
                """), "xs");
        Ast.TypeRef right = bindingType(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector<growth_policy=GP.Foo, allocator=Arena, align=64>[int] = [1];
                  return;
                }
                """), "xs");
        assertEquals(left, right);

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("""
                        pub fnc main(): void {
                          @Align<64>
                          val xs: Vector<align=32>[int] = [1];
                          return;
                        }
                        """));
        assertTrue(duplicate.getMessage().contains("specified both inline and by annotation"));
    }

    @Test
    void runtimeSizeAndCapacityAreNotTypeMetadata() {
        IllegalArgumentException size = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: Vector<size=0>[int] = [];
                          return;
                        }
                        """)));
        assertTrue(size.getMessage().contains("runtime instance state"));

        IllegalArgumentException capacity = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: Vector<capacity=32>[int] = [];
                          return;
                        }
                        """)));
        assertTrue(capacity.getMessage().contains("runtime instance state"));
    }

    @Test
    void validatesCompileTimeCollectionPolicies() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector<
                      allocator=Arena,
                      growth_policy=GP.Foo,
                      align=64,
                      inline_capacity=32,
                      max_capacity=4096
                  >[int] = [1, 2, 3];
                  return;
                }
                """)));

        IllegalArgumentException alignment = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val xs: Vector<align=48>[int] = [1];
                          return;
                        }
                        """)));
        assertTrue(alignment.getMessage().contains("power of two"));
    }

    @Test
    void multidimensionalBracketShorthandCreatesNestedHomogeneousCollectionType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector[int][int] = [[1, 2], [3, 4]];
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val xs: Vector<rank=2>[int] = [[1, 2], [3, 4]];
                  return;
                }
                """)));
    }


    @Test
    void tupleSupportsNamedStorageMetadataAndFixedSizeAssertion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  val pair: Tuple<
                      growth_policy=GP.Fixed,
                      allocator=Arena,
                      align=64,
                      size=2
                  >[int, string] = (7, "seven");
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc main(): void {
                  @NamedParams<size=2, growth_policy=GP.Fixed, align=64>
                  val pair: [int, string] = (7, "seven");
                  return;
                }
                """)));

        IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val pair: Tuple<size=3>[int, string] = (7, "seven");
                          return;
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("disagrees with fixed sequence arity 2"));
    }

    @Test
    void tupleAndFixedArrayRemainNominallyDistinctFiniteSequences() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc takesTuple(Tuple[int, string] value): void { return; }

                pub fnc main(): void {
                  val pair: Tuple[int, string] = (7, "seven");
                  takesTuple(pair);
                  return;
                }
                """)));

        IllegalArgumentException fixedArrayIsNotTuple = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc takesTuple(Tuple[int, string] value): void { return; }

                        pub fnc main(): void {
                          val pair: FixedArray[int, string] = [7, "seven"];
                          takesTuple(pair);
                          return;
                        }
                        """)));
        assertTrue(fixedArrayIsNotTuple.getMessage().contains("argument"));

        IllegalArgumentException tupleIsNotFixedArray = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc takesFixed(FixedArray[int, string] value): void { return; }

                        pub fnc main(): void {
                          val pair: Tuple[int, string] = (7, "seven");
                          takesFixed(pair);
                          return;
                        }
                        """)));
        assertTrue(tupleIsNotFixedArray.getMessage().contains("argument"));
    }

    @Test
    void fixedSequenceMetadataRejectsImpossiblePhysicalLayout() {
        IllegalArgumentException inlineCapacity = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val pair: Tuple<inline_capacity=1>[int, string] = (7, "seven");
                          return;
                        }
                        """)));
        assertTrue(inlineCapacity.getMessage().contains("smaller than fixed sequence arity 2"));

        IllegalArgumentException rank = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val pair: Tuple<rank=2>[int, string] = (7, "seven");
                          return;
                        }
                        """)));
        assertTrue(rank.getMessage().contains("requires rank=1"));

        IllegalArgumentException capacity = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        pub fnc main(): void {
                          val pair: Tuple<capacity=2>[int, string] = (7, "seven");
                          return;
                        }
                        """)));
        assertTrue(capacity.getMessage().contains("runtime instance state"));
    }

    private static Ast.TypeRef bindingType(Ast.Program program, String name) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl function)) continue;
                for (Ast.Stmt statement : function.body()) {
                    if (statement instanceof Ast.BindingStmt binding && binding.name().equals(name)) {
                        return binding.declaredType();
                    }
                }
            }
        }
        throw new AssertionError("missing binding " + name);
    }
}

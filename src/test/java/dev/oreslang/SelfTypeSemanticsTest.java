package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelfTypeSemanticsTest {
    @Test
    void selfReturnAcceptsTheExactReceiver() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class Foo as
                    pub same(): self {
                      return self;
                    }
                  end
                end
                """)));
    }

    @Test
    void selfReturnRejectsAFreshInstanceOfTheSameNominalClass() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Foo as
                            pub wrong(): self {
                              return new Foo();
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("return value"), error::getMessage);
        assertTrue(error.getMessage().contains("SelfType"), error::getMessage);
    }

    @Test
    void inferredAliasesOfSelfPreserveTheExactReceiverProof() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module m
                  define class Foo as
                    pub same(): self {
                      val alias = self;
                      return alias;
                    }
                  end
                end
                """)));
    }

    @Test
    void anotherValueAlreadyTypedAsSelfMayBeReturned() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub choose(self other): self {
                      return other;
                    }
                  end

                  define class Child extends Base as
                    pub child_only(): int {
                      return 8;
                    }
                  end
                end

                pub routine main(): void {
                  val left = new m.Child();
                  val right = new m.Child();
                  stdio.stdout.write(left.choose(right).child_only());
                }
                """);

        assertEquals("8", output);
    }

    @Test
    void explicitlyWideningSelfToTheClassLosesThePolymorphicProof() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Foo as
                            pub wrong(): self {
                              val Foo widened = self;
                              return widened;
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("return value"), error::getMessage);
        assertTrue(error.getMessage().contains("SelfType"), error::getMessage);
    }

    @Test
    void inheritedSelfReturnRebindsToTheActualChildReceiverForFluentChaining() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub same(): self {
                      return self;
                    }
                  end

                  define class Child extends Base as
                    pub child_only(): int {
                      return 7;
                    }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.same().child_only());
                }
                """);

        assertEquals("7", output);
    }

    @Test
    void genericParentSelfReturnRebindsToTheConcreteChild() throws Exception {
        String output = run("""
                define module m
                  define class Box<T> as
                    pub same(): self {
                      return self;
                    }
                  end

                  define class Child extends Box<int> as
                    pub child_only(): int {
                      return 11;
                    }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.same().child_only());
                }
                """);

        assertEquals("11", output);
    }

    @Test
    void contextualBoundMethodExtractionKeepsSelfPolymorphic() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub same(): self {
                      return self;
                    }
                  end

                  define class Child extends Base as
                    pub child_only(): int {
                      return 12;
                    }

                    pub via_callback(): int {
                      val Fnc<self> callback = self.same;
                      return callback().child_only();
                    }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.via_callback());
                }
                """);

        assertEquals("12", output);
    }

    @Test
    void childOverrideCanKeepTheSameReceiverPolymorphicContract() throws Exception {
        String output = run("""
                define module m
                  define class Base as
                    pub same(): self {
                      return self;
                    }
                  end

                  define class Child extends Base as
                    pub same(): self {
                      return self;
                    }

                    pub child_only(): int {
                      return 9;
                    }
                  end
                end

                pub routine main(): void {
                  val child = new m.Child();
                  stdio.stdout.write(child.same().child_only());
                }
                """);

        assertEquals("9", output);
    }

    @Test
    void concreteChildReturnCannotReplaceAnInheritedSelfContract() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define module m
                          define class Base as
                            pub same(): self {
                              return self;
                            }
                          end

                          define class Child extends Base as
                            pub same(): Child {
                              return self;
                            }
                          end
                        end
                        """)));

        assertTrue(error.getMessage().contains("conflicting member"), error::getMessage);
    }

    @Test
    void interfacesAndStructuralCallsPreserveSelfAcrossParametersAndReturns() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub interface Fluent {
                  fnc choose(self other) => self;
                }

                fnc use_structurally(value structural Fluent): void {
                  value.choose(value).choose(value);
                  return;
                }

                define module m
                  define class Foo implements Fluent as
                    pub choose(self other): self {
                      return other;
                    }
                  end
                end
                """)));
    }

    @Test
    void selfTypeIsRejectedOutsideAnInstanceReceiverContext() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(): self {
                          return 1;
                        }
                        """)));

        assertTrue(error.getMessage().contains("only available in an instance receiver context"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "self-type.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}

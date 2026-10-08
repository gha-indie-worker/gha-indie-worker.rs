package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralTuplePatternMatchTest {
    @Test
    void namedTraitAndInlineStructuralPatternsTypeCheckWithoutNominalImplements() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define module app
                      define trait R as
                        bar: string;
                        fnc foo(string value) => void;
                      end

                      define class Base as
                      end

                      define class Candidate extends Base as
                        pub val string bar = "ok";
                        pub foo(string value): void { return; }
                      end

                      fnc named(Base value): int {
                        match value over
                          on structural R r -> {
                            r.foo(r.bar);
                            return 1;
                          }
                          on _ -> { return 0; }
                        end
                      }

                      fnc inline(Base value): string {
                        match value over
                          on structural { bar: string } v -> {
                            return v.bar;
                          }
                          on _ -> { return "missing"; }
                        end
                      }
                    end
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void tuplePatternsComposeNominalStructuralAndWildcardPatterns() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Base as
                  end

                  define class Candidate extends Base as
                    pub val string bar = "ok";
                  end

                  define class Dog as
                  end

                  fnc classify(Base value, Dog dog): int {
                    match (value, dog) over
                      on (structural { bar: string } shaped, Dog g) -> {
                        return 7;
                      }
                      on (_, _) -> {
                        return 0;
                      }
                    end
                  }
                end
                """)));
    }

    @Test
    void structuralWidthOrderingRejectsProvablyDeadNarrowerArm() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module app
                          define class Base as
                          end

                          fnc classify(Base value): int {
                            match value over
                              on structural { foo: string } broad -> {
                                return 1;
                              }
                              on structural { foo: string, bar: int } narrow -> {
                                return 2;
                              }
                              on _ -> {
                                return 0;
                              }
                            end
                          }
                        end
                        """)));

        assertTrue(error.getMessage().contains("unreachable match arm"));
    }

    @Test
    void structuralPatternsPreserveInferredStructReadonlyCapability() {
        for (String target : new String[]{"{ bar: string }", "Readable"}) {
            String source = """
                    define trait Readable as
                      bar: string;
                    end
                    fnc bad(): void {
                      const value = infer struct{bar: "before"};
                      match value over
                        on structural %s narrowed -> {
                          narrowed.bar = "after";
                        }
                        on _ -> { return; }
                      end
                      return;
                    }
                    """.formatted(target);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)));
            assertTrue(error.getMessage().contains("readonly"), error.getMessage());
        }
    }

    @Test
    void structuralPatternCanReadCanonicalInferredStructWithContextualGuard() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    fnc read(): string {
                      const when = true;
                      const value = infer struct{bar: "ok"};
                      match value over
                        on structural { bar: string } narrowed when when -> {
                          return narrowed.bar;
                        }
                        on _ -> { return "missing"; }
                      end
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void listPatternsAndDefaultMatchArmsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(List<int> values): int {
                  match values over
                    on [_, _] -> { return 1; }
                    on _ -> { return 0; }
                  end
                }
                """));

        IllegalArgumentException defaultArm = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        fnc bad(bool value): int {
                          match value over
                            default -> { return 1; }
                          end
                        }
                        """));
        assertTrue(defaultArm.getMessage().contains("no default/else keyword"));
    }

    @Test
    void runtimeExecutesStructuralTraitInlineAndTuplePatterns() throws Exception {
        String program = """
                define module app
                  define trait R as
                    bar: string;
                    fnc foo(string value) => void;
                  end

                  define class Base as
                  end

                  define class Candidate extends Base as
                    pub val string bar = "ok";
                    pub foo(string value): void { return; }
                  end

                  define class Dog as
                  end

                  fnc named(Base value): int {
                    match value over
                      on structural R r -> {
                        r.foo(r.bar);
                        return 1;
                      }
                      on _ -> { return 0; }
                    end
                  }

                  fnc inline(Base value): string {
                    match value over
                      on structural { bar: string } v -> { return v.bar; }
                      on _ -> { return "missing"; }
                    end
                  }

                  fnc tupled(Base value, Dog dog): int {
                    match (value, dog) over
                      on (structural { bar: string } v, Dog g) -> { return 7; }
                      on (_, _) -> { return 0; }
                    end
                  }

                  pub fnc main(): void {
                    // Function arguments obey ownership transfer. Use fresh
                    // values here so this test isolates structural/tuple
                    // matching rather than accidentally testing reuse-after-move.
                    stdio.println(named(new Candidate()));
                    stdio.println(inline(new Candidate()));
                    stdio.println(tupled(new Candidate(), new Dog()));
                    return;
                  }
                end
                """;

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "structural-tuple-match.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("1"));
        assertTrue(text.contains("ok"));
        assertTrue(text.contains("7"));
    }
}

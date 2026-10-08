package dev.oreslang;

import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class PatternMatchingHardeningTest {
    @Test
    void matchAndSwitchUseContextualArmKeywordsAndSlimArrows() {
        var tokens = new Lexer("is matches match when case default first switch on").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IS));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCHES));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.MATCH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("when")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.DEFAULT));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("case")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("on")));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.IDENT && t.lexeme().equals("first")));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc contextualNames(): int {
                  val int on = 1;
                  val int case = 2;
                  val int when = 3;
                  return on + case + when;
                }
                """)));

        assertDoesNotThrow(() -> Parser.parse("""
                fnc contextualArms(int value): void {
                  do select {
                    when readch input: {
                    }
                    case writech output, value: {
                    }
                  }

                  switch value
                    case 1 -> {
                      return;
                    }
                    default -> {
                      return;
                    }
                  end
                }

                fnc guarded(int value): int {
                  match value over
                    on int n when n > 0 -> {
                      return n;
                    }
                    on _ -> {
                      return 0;
                    }
                  end
                }
                """));

        IllegalArgumentException fatMatch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> value): void {
                  match value over
                    on Some(v) => { return; }
                    on None -> { return; }
                  end
                }
                """));
        assertTrue(fatMatch.getMessage().contains("slim arrow"));

        IllegalArgumentException fatSwitch = assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(int value): void {
                  switch value
                    case 1 => { return; }
                    default -> { return; }
                  end
                }
                """));
        assertTrue(fatSwitch.getMessage().contains("slim arrow"));
    }

    @Test
    void isNarrowsAndBindsInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc acceptDog(Dog dog): int {
                      return 7;
                    }

                    fnc classify(Animal animal): int {
                      if animal is type Dog dog then
                        return acceptDog(dog);
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void matchesCanDestructureSumTypesInsideIfFi() {
        assertDoesNotThrow(() -> {
            var typed = TypeChecker.check(Parser.parse("""
                    fnc valueOrZero(Option<int> value): int {
                      if value matches Some(inner) then
                        return inner;
                      else
                        return 0;
                      fi
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void orderedMatchUsesFirstMatchAndRejectsProvablyDeadLaterArms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc valueOrZero(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                    on None -> { return 0; }
                  end
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match animal over
                    on Dog dog -> { return 1; }
                    on Animal any -> { return 2; }
                  end
                }
                """)));

        IllegalArgumentException unreachable = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  match animal over
                    on Animal any -> { return 2; }
                    on Dog dog -> { return 1; }
                  end
                }
                """)));
        assertTrue(unreachable.getMessage().contains("unreachable match arm"));
    }

    @Test
    void orderedGuardsMayOverlapAndWildcardIsTheFallback() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int n): int {
                  match n over
                    on int x when x >= 0 -> { return 1; }
                    on int x when x <= 10 -> { return 2; }
                    on _ -> { return 3; }
                  end
                }
                """)));

        IllegalArgumentException dead = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(int n): int {
                  match n over
                    on _ -> { return 1; }
                    on int x -> { return x; }
                  end
                }
                """)));
        assertTrue(dead.getMessage().contains("unreachable match arm"));
    }

    @Test
    void canonicalMatchRequiresOnAndExhaustivenessWhileLegacyMatchRemainsAccepted() {
        // Compatibility: the pre-over statement grammar remains accepted so
        // existing Oreslang does not break merely because canonical match
        // gained explicit "over" / "on" boundaries.
        assertDoesNotThrow(() -> Parser.parse("""
                fnc legacy(Option<int> value): int {
                  match value
                    Some(inner) -> { return inner; }
                  end
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> value): int {
                  match value over
                    Some(inner) -> { return inner; }
                  end
                }
                """));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc incomplete(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                  end
                }
                """)));
        assertTrue(error.getMessage().contains("non-exhaustive match"));
    }

    @Test
    void switchIsConstantDispatchAndRejectsDuplicateCases() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 2, 3 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));

        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc classify(int value): int {
                  switch value
                    case 1 -> { return 10; }
                    case 1 -> { return 20; }
                    default -> { return 0; }
                  end
                }
                """)));
        assertTrue(duplicate.getMessage().contains("duplicate switch case"));
    }

    @Test
    void castsAreCheckedAndOptionalWithoutJavaHostTypeSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc checked(Animal animal): Dog {
                  return animal as Dog;
                }

                fnc optional(Animal animal): Option<Dog> {
                  return animal as? Dog;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class Dog as
                end

                fnc impossible(int value): Dog {
                  return value as Dog;
                }
                """)));
    }

    @Test
    void runtimeExecutesNativeSemanticTypeAndPatternRelations() throws Exception {
        String program = """
                define class Animal as
                end

                define class Dog extends Animal as
                end

                fnc classify(Animal animal): int {
                  if animal is type Dog dog then
                    return 7;
                  else
                    return 0;
                  fi
                }

                fnc fromOption(Option<int> value): int {
                  match value over
                    on Some(inner) -> { return inner; }
                    on None -> { return 0; }
                  end
                }

                fnc fromSwitch(int value): int {
                  switch value
                    case 1 -> { return 11; }
                    case 2 -> { return 22; }
                    default -> { return 0; }
                  end
                }

                pub fnc main(): void {
                  val Animal animal = new Dog();
                  stdio.println(classify(animal));
                  stdio.println(fromOption(Some(42)));
                  stdio.println(fromSwitch(2));
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "pattern-runtime.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("7"));
        assertTrue(text.contains("42"));
        assertTrue(text.contains("22"));
    }

    @Test
    void refinementAliasCannotDuplicateMoveOnlyOwnership() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> {
            var typed = TypeChecker.check(Parser.parse("""
                    define class Animal as
                    end

                    define class Dog extends Animal as
                    end

                    fnc consume(Dog dog): void {
                      return;
                    }

                    fnc bad(Animal animal): void {
                      if animal is type Dog dog then
                        consume(dog);
                        consume(dog);
                      fi
                      return;
                    }
                    """));
            OwnershipChecker.check(typed);
        });
        assertTrue(error.getMessage().contains("moved value"));
    }
}

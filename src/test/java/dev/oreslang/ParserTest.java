package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Lexer;
import dev.oreslang.parser.Parser;
import dev.oreslang.parser.Token;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ParserTest {
    @Test
    void exhaustivelyAcceptsSupportedDeclarationModifierPermutations() {
        for (List<String> order : permutations("define", "class", "pub", "abstract")) {
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s Box as
                      end
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }

        for (List<String> order : permutations("define", "interface", "pub")) {
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s Api {
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }

        for (List<String> order : permutations("define", "contract", "pub")) {
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s Contract {
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }

        for (List<String> order : permutations("pub", "async", "nlex", "fnc")) {
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s work(): void {
                        return;
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }

        for (List<String> order : permutations("pub", "static", "fnc")) {
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      define class Box as
                        %s helper(): int {
                          return 1;
                        }
                      end
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }

        int actorCases = 0;
        for (List<String> order : permutations("pub", "async", "untrusted", "actor", "fnc")) {
            actorCases++;
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s worker(int value): int {
                        return value;
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }
        assertEquals(120, actorCases);

        int actorRoutineCases = 0;
        for (List<String> order : permutations("pub", "async", "shared", "actor", "routine")) {
            actorRoutineCases++;
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s worker_routine(int value): int {
                        return value;
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }
        assertEquals(120, actorRoutineCases);

        int isoActorCases = 0;
        for (List<String> order : permutations("pub", "async", "isoactor", "fnc")) {
            isoActorCases++;
            assertDoesNotThrow(() -> Parser.parse("""
                    define module app as
                      %s private_worker(int value): int {
                        return value;
                      }
                    end
                    """.formatted(String.join(" ", order))), String.join(" ", order));
        }
        assertEquals(24, isoActorCases);
    }

    @Test
    void classDefineAndVisibilityKeywordsMayAppearInCompatibilityOrder() {
        Ast.Program program = Parser.parse("""
                define module app as
                  class pub define A as
                  end

                  pub class define B as
                  end

                  define class pub C as
                  end

                  define pub class D as
                  end

                  pub define class E as
                  end
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        assertEquals(5, module.declarations().size());
        for (Ast.Decl declaration : module.declarations()) {
            Ast.ClassDecl klass = assertInstanceOf(Ast.ClassDecl.class, declaration);
            assertEquals(Ast.Visibility.PUBLIC, klass.visibility());
        }
    }

    @Test
    void interfaceAndContractDefineKeywordsMayAppearInCompatibilityOrder() {
        Ast.Program program = Parser.parse("""
                define module app as
                  interface pub define ApiA {
                  }

                  pub interface define ApiB {
                  }

                  define interface pub ApiC {
                  }

                  contract pub define ContractA {
                  }

                  pub contract define ContractB {
                  }

                  define contract pub ContractC {
                  }
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        assertEquals(6, module.declarations().size());
        for (int i = 0; i < 3; i++) {
            Ast.InterfaceDecl iface = assertInstanceOf(Ast.InterfaceDecl.class, module.declarations().get(i));
            assertEquals(Ast.Visibility.PUBLIC, iface.visibility());
            assertFalse(iface.moduleContract());
        }
        for (int i = 3; i < 6; i++) {
            Ast.InterfaceDecl contract = assertInstanceOf(Ast.InterfaceDecl.class, module.declarations().get(i));
            assertEquals(Ast.Visibility.PUBLIC, contract.visibility());
            assertTrue(contract.moduleContract());
        }
    }

    @Test
    void actorMarkerMayAppearOnEitherSideOfCallableKind() {
        Ast.Program program = Parser.parse("""
                define module app as
                  fnc pub actor untrusted async first(int value): int {
                    return value;
                  }

                  routine shared pub actor async second(int value): int {
                    return value;
                  }

                  fnc pub isoactor async third(int value): int {
                    return value;
                  }
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl first = assertInstanceOf(Ast.FunctionDecl.class, module.declarations().get(0));
        Ast.FunctionDecl second = assertInstanceOf(Ast.FunctionDecl.class, module.declarations().get(1));
        Ast.FunctionDecl third = assertInstanceOf(Ast.FunctionDecl.class, module.declarations().get(2));

        assertEquals(Ast.ActorKind.UNTRUSTED, first.actorKind());
        assertEquals(Ast.ActorKind.SHARED, second.actorKind());
        assertEquals(Ast.ActorKind.PRIVATE, third.actorKind());
        assertTrue(first.async());
        assertTrue(second.async());
        assertTrue(third.async());
    }

    @Test
    void modifiersAfterFncWorkAcrossCallableContexts() {
        Ast.Program program = Parser.parse("""
                define module app as
                  fnc pub async module_work(): int {
                    return 1;
                  }

                  define pub class Box as {
                    fnc pub static helper(): int {
                      return 2;
                    }
                  }

                  actor pub Worker {
                    fnc pub async handle(int value): int {
                      return value;
                    }
                  }

                  define interface Api {
                    fnc structural render() => String;
                  }

                  actor pub fnc untrusted async worker(int value): int {
                    return value;
                  }
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl moduleWork = assertInstanceOf(Ast.FunctionDecl.class, module.declarations().get(0));
        Ast.ClassDecl box = assertInstanceOf(Ast.ClassDecl.class, module.declarations().get(1));
        Ast.ClassDecl actor = assertInstanceOf(Ast.ClassDecl.class, module.declarations().get(2));
        Ast.InterfaceDecl api = assertInstanceOf(Ast.InterfaceDecl.class, module.declarations().get(3));
        Ast.FunctionDecl actorFunction = assertInstanceOf(Ast.FunctionDecl.class, module.declarations().get(4));

        assertEquals(Ast.Visibility.PUBLIC, moduleWork.visibility());
        assertTrue(moduleWork.async());

        Ast.MethodDecl helper = box.methods().getFirst();
        assertTrue(helper.isStatic());
        assertEquals(Ast.Visibility.PUBLIC, helper.visibility());

        assertEquals(Ast.Visibility.PUBLIC, actor.visibility());
        Ast.MethodDecl handle = actor.methods().getFirst();
        assertTrue(handle.async());
        assertEquals(Ast.Visibility.PUBLIC, handle.visibility());

        Ast.InterfaceFunctionDecl render =
                assertInstanceOf(Ast.InterfaceFunctionDecl.class, api.members().getFirst());
        assertTrue(render.structural());

        assertEquals(Ast.ActorKind.UNTRUSTED, actorFunction.actorKind());
        assertEquals(Ast.Visibility.PUBLIC, actorFunction.visibility());
        assertTrue(actorFunction.async());
    }

    @Test
    void unsupportedModifiersAreNeverSilentlyDiscarded() {
        List<String> invalid = List.of(
                """
                private define module app as
                end
                """,
                """
                define module app as
                  async type Alias = int;
                end
                """,
                """
                define module app as
                  define class Box as
                    async val int value = 1;
                  end
                end
                """,
                """
                define module app as
                  define class Box as
                    structural constructor() {
                      return;
                    }
                  end
                end
                """,
                """
                define module app as
                  actor Worker {
                    generator let value = 1;
                  }
                end
                """,
                """
                define module app as
                  actor Worker {
                    untrusted fnc work(): void {
                      return;
                    }
                  }
                end
                """,
                """
                define module app as
                  define interface Api {
                    fnc generator render() => String;
                  }
                end
                """,
                """
                define module app as
                  define interface Api {
                    private fnc render() => String;
                  }
                end
                """,
                """
                define module app as
                  define class Box as
                    static fnc static helper(): int {
                      return 1;
                    }
                  end
                end
                """,
                """
                define module app as
                  isoactor fnc shared contradictory(): void {
                    return;
                  }
                end
                """,
                """
                define module app as
                  shared fnc missing_actor(): void {
                    return;
                  }
                end
                """,
                """
                define module app as
                  fnc untrusted missing_actor_after_fnc(): void {
                    return;
                  }
                end
                """,
                """
                define module app as
                  fnc shared isoactor contradictory_after_fnc(): void {
                    return;
                  }
                end
                """
        );

        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void declarationModifiersMayAppearOnEitherSideOfDefineAndCallableKind() {
        Ast.Program program = Parser.parse("""
                define module app as
                  pub define class LegacyBox as
                  end

                  define pub abstract class CanonicalBox as
                  end

                  define pub class BracedBox as {
                  }

                  fnc pub async late_visibility(): void {
                    return;
                  }

                  async nlex fnc pub mixed_order(): void {
                    return;
                  }

                  pub async nlex fnc canonical_order(): void {
                    return;
                  }
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.ClassDecl legacyClass = (Ast.ClassDecl) module.declarations().get(0);
        Ast.ClassDecl canonicalClass = (Ast.ClassDecl) module.declarations().get(1);
        Ast.ClassDecl bracedClass = (Ast.ClassDecl) module.declarations().get(2);
        Ast.FunctionDecl lateVisibility = (Ast.FunctionDecl) module.declarations().get(3);
        Ast.FunctionDecl mixedOrder = (Ast.FunctionDecl) module.declarations().get(4);
        Ast.FunctionDecl canonicalOrder = (Ast.FunctionDecl) module.declarations().get(5);

        assertEquals(Ast.Visibility.PUBLIC, legacyClass.visibility());
        assertEquals(Ast.Visibility.PUBLIC, canonicalClass.visibility());
        assertEquals(Ast.Visibility.PUBLIC, bracedClass.visibility());
        assertTrue(canonicalClass.isAbstract());

        assertEquals(Ast.Visibility.PUBLIC, lateVisibility.visibility());
        assertTrue(lateVisibility.async());

        assertEquals(Ast.Visibility.PUBLIC, mixedOrder.visibility());
        assertTrue(mixedOrder.async());
        assertTrue(mixedOrder.nonLexical());

        assertEquals(canonicalOrder.visibility(), mixedOrder.visibility());
        assertEquals(canonicalOrder.async(), mixedOrder.async());
        assertEquals(canonicalOrder.nonLexical(), mixedOrder.nonLexical());
    }

    @Test
    void duplicateModifiersRemainRejectedAcrossDeclarationKeywords() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app as
                  pub fnc pub nope(): void {
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app as
                  pub define pub class Nope as
                  end
                end
                """));
    }

    @Test
    void supportsMultipleModulesAndComplexNumbers() {
        String source = """
                define module math
                  fnc z(): complex {
                    return 3 + 4i;
                  }
                end

                define module app
                  pub fnc main(): void {
                    const answer = 40 + 2;
                    [const first, let second] = [1, 2];
                    stdio.println("oreslang");
                    return;
                  }
                end
                """;

        Ast.Program program = TypeChecker.check(Parser.parse(source));
        assertEquals(2, program.modules().size());
        assertEquals("math", program.modules().getFirst().name());
    }

    @Test
    void parsesIfDoFiWithCommaAndPipeConditions() {
        String source = """
                define module app
                  fnc choose(bool a, bool b): int {
                    if a, b | false; do
                      return 1;
                    else
                      return 0;
                    fi
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void parsesMixedThenAndDoAcrossElseifAndElifBranches() {
        String source = """
                define module app as
                  fnc choose(bool a, bool b, bool c, bool d): int {
                    if a; then
                      return 1;
                    elif b; do
                      return 2;
                    elseif c; then
                      return 3;
                    elif d; do
                      return 4;
                    else
                      return 5;
                    fi
                  }
                end
                """;

        Parser.ParseResult parsed = Parser.parseWithWarnings(source);
        assertDoesNotThrow(() -> TypeChecker.check(parsed.program()));
        assertEquals(2, parsed.warnings().size());
        assertTrue(parsed.warnings().stream()
                .allMatch(warning -> warning.message().contains("use 'then'")));
    }

    @Test
    void lexerNormalizesElifAndElseifToTheSameBranchToken() {
        var tokens = new Lexer("if a; then elif b; do elseif c; then else fi").scan();
        var branchTokens = tokens.stream()
                .filter(token -> token.type() == Token.Type.ELSEIF)
                .toList();

        assertEquals(2, branchTokens.size());
        assertEquals(List.of("elif", "elseif"), branchTokens.stream().map(Token::lexeme).toList());
    }

    @Test
    void canonicalIfThenSupportsElifAndSingleFiElseIf() {
        Ast.Program elifProgram = Parser.parse("""
                define module app as
                  fnc choose(int value): int {
                    if value < 0; then
                      return -1;
                    elif value == 0; then
                      return 0;
                    else
                      return 1;
                    fi
                  }
                end
                """);

        Ast.FunctionDecl elifFunction = (Ast.FunctionDecl) elifProgram.modules().getFirst().declarations().getFirst();
        Ast.IfStmt elif = (Ast.IfStmt) elifFunction.body().getFirst();
        assertEquals(2, elif.branches().size());
        assertFalse(elif.elseBody().isEmpty());

        Ast.Program elseIfProgram = Parser.parse("""
                define module app as
                  fnc choose(int value): int {
                    if value < 0; then
                      return -1;
                    else if value == 0; then
                      return 0;
                    else
                      return 1;
                    fi
                  }
                end
                """);

        Ast.FunctionDecl elseIfFunction = (Ast.FunctionDecl) elseIfProgram.modules().getFirst().declarations().getFirst();
        Ast.IfStmt elseIf = (Ast.IfStmt) elseIfFunction.body().getFirst();
        assertEquals(2, elseIf.branches().size());
        assertFalse(elseIf.elseBody().isEmpty());
    }

    @Test
    void legacyIfDoAndElseifRemainAcceptedForMigration() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module app as
                  fnc choose(int value): int {
                    if value < 0; do
                      return -1;
                    elseif value == 0; do
                      return 0;
                    else
                      return 1;
                    fi
                  }
                end
                """));
    }

    @Test
    void conditionalDoProducesDeprecationWarningsButThenDoesNot() {
        Parser.ParseResult legacy = Parser.parseWithWarnings("""
                define module app as
                  fnc choose(bool first, bool second): int {
                    if first; do
                      return 1;
                    elseif second; do
                      return 2;
                    else
                      return 3;
                    fi
                  }
                end
                """);
        assertEquals(2, legacy.warnings().size());
        assertTrue(legacy.warnings().stream()
                .allMatch(warning -> warning.message().contains("use 'then'")));

        Parser.ParseResult canonical = Parser.parseWithWarnings("""
                define module app as
                  fnc choose(bool first, bool second): int {
                    if first; then
                      return 1;
                    elif second; then
                      return 2;
                    else
                      return 3;
                    fi
                  }
                end
                """);
        assertTrue(canonical.warnings().isEmpty());
    }

    @Test
    void methodReceiverIsImplicitOrExplicitSelf() {
        String source = """
                define module model
                  define class x as
                    @Ret<self>
                    find() {
                      return self;
                    }

                    @Ret<self>
                    find_with_arg(self x)(int foo) {
                      return self;
                    }
                  end
                end
                """;
        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl klass = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();
        assertNull(klass.methods().getFirst().explicitReceiverType());
        assertEquals("x", klass.methods().get(1).explicitReceiverType().name());
    }

    @Test
    void lexerRecognizesExecutableAndTypeArrows() {
        var tokens = new Lexer("|| -> { return; }; type F = () => void;").scan();
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.ARROW));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == Token.Type.FAT_ARROW));
    }

    @Test
    void callableDeclarationSyntaxSeparatesCodeFromFunctionTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub fnc run(): (() => void) {
                  return || -> {
                    return;
                  };
                }

                pub routine helper = || -> {
                  return;
                }

                pub fnc no_result = || -> {
                  helper();
                  return;
                }

                pub routine main = || -> void {
                  val (() => void) callback = run();
                  callback();
                  no_result();
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub fnc bad_implicit_void = || -> {
                  return 1;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub fnc bad() => void { return; }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                type Bad = () -> void;
                """));
    }
    @Test
    void namedExecutableCallablesAcceptColonOrSlimArrowReturns() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc by_colon(int value): int {
                  return value;
                }

                routine by_arrow(int value) -> int {
                  return value;
                }

                define class Box as
                  pub value() -> int {
                    return 1;
                  }

                  pub static fnc twice(int value) -> int {
                    return value * 2;
                  }
                end

                actor Worker {
                  pub handle(int value) -> int {
                    return value;
                  }
                }

                pub routine main() -> void {
                  val box = new Box();
                  stdio.stdout.write(by_colon(1));
                  stdio.stdout.write(by_arrow(2));
                  stdio.stdout.write(box.value());
                  stdio.stdout.write(Box.twice(2));
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc still_type_only() => int {
                  return 1;
                }
                """));
    }

    @Test
    void parsesSharedActorAndAllowsMailboxOwnedStateMutation() {
        String source = """
                shared actor Account {
                  let balance = 100;

                  pub fnc withdraw(int amount): void {
                    self.balance = self.balance - amount;
                    return;
                  }
                }
                """;

        Ast.Program program = Parser.parse(source);
        Ast.ClassDecl actor = (Ast.ClassDecl) program.modules().getFirst().declarations().getFirst();

        assertEquals(Ast.ActorKind.SHARED, actor.actorKind());
        assertEquals("Account", actor.name());
        assertEquals("withdraw", actor.methods().getFirst().name());

        Ast.Program typed = TypeChecker.check(program);
        assertDoesNotThrow(() -> OwnershipChecker.check(typed));
    }

    @Test
    void actorFncDefaultsSharedAndIsoactorIsPrivate() {
        Ast.Program sharedProgram = Parser.parse("""
                pub actor fnc worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl sharedActor = (Ast.FunctionDecl) sharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, sharedActor.actorKind());

        Ast.Program explicitSharedProgram = Parser.parse("""
                pub shared actor fnc worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl explicitShared = (Ast.FunctionDecl) explicitSharedProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.SHARED, explicitShared.actorKind());

        Ast.Program privateProgram = Parser.parse("""
                pub isoactor routine worker(int value): int {
                  return value;
                }
                """);
        Ast.FunctionDecl privateActor = (Ast.FunctionDecl) privateProgram.modules().getFirst().declarations().getFirst();
        assertEquals(Ast.ActorKind.PRIVATE, privateActor.actorKind());
        assertEquals(Ast.CallableKind.ROUTINE, privateActor.kind());
    }

    @Test
    void sharedWithoutActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared fnc nope(): void {
                  return;
                }
                """));
    }

    @Test
    void actorCallablesMayBeCalledButActorClassesCannotBeConstructedOrdinarily() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub actor fnc worker(int value): int {
                  return value;
                }

                pub fnc good(): int {
                  return worker(1);
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Account {
                  let int balance = 100;

                  pub fnc read(): int {
                    return self.balance;
                  }
                }

                pub fnc bad(): Account {
                  return new Account();
                }
                """)));
    }

    @Test
    void rejectsDuplicateAndConflictingActorModifiers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                shared shared actor Account {
                  let balance = 1;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub private actor fnc worker(): void {
                  return;
                }
                """));
    }

    @Test
    void actorFunctionCannotBeProgramMain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc main(): void {
                  return;
                }
                """)));
    }


    @Test
    void actorSelfAndMutableActorStateCannotEscapeMailboxTurn() {
        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): Account {
                        return self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): &mut Account {
                        return &mut self;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc leak(): &mut Account {
                        val alias = &mut self;
                        return alias;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let Array<int> items = [1, 2, 3];

                      pub fnc leak(): Array<int> {
                        return self.items;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }

    @Test
    void actorMayReturnCopyLikeStateButCannotPassSelfBorrowToOrdinaryFunction() {
        assertDoesNotThrow(() -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    shared actor Account {
                      let balance = 100;

                      pub fnc current(): int {
                        return self.balance;
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });

        assertThrows(IllegalArgumentException.class, () -> {
            Ast.Program typed = TypeChecker.check(Parser.parse("""
                    fnc inspect(&Account account): int {
                      return 1;
                    }

                    shared actor Account {
                      let balance = 100;

                      pub fnc inspectSelf(): int {
                        return inspect(&self);
                      }
                    }
                    """));
            OwnershipChecker.check(typed);
        });
    }


    @Test
    void actorInheritanceMustPreserveIsolationKind() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                }

                shared actor Child extends Parent {
                  pub fnc current(): int {
                    return 1;
                  }
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                isoactor Parent {
                }

                shared actor Child extends Parent {
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                shared actor Child extends Object {
                  let value = 1;
                }
                """)));
    }



@Test
    void parsesReusableUnderscoreDestructureDiscards() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define module app
                  pub fnc main(): void {
                    [const foo, _, let bar] = (1, 2, 3);
                    [_, _, const tail] = (4, 5, 6);
                    [const z, _, let y] = (7, 8, 9);
                    stdio.println(foo);
                    stdio.println(bar);
                    stdio.println(tail);
                    stdio.println(z);
                    stdio.println(y);
                    return;
                  }
                end
                """));

        Ast.FunctionDecl main = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.DestructureStmt first = (Ast.DestructureStmt) main.body().getFirst();
        assertFalse(first.bindings().getFirst().isDiscard());
        assertTrue(first.bindings().get(1).isDiscard());

        Ast.DestructureStmt second = (Ast.DestructureStmt) main.body().get(1);
        assertTrue(second.bindings().getFirst().isDiscard());
        assertTrue(second.bindings().get(1).isDiscard());
        assertFalse(second.bindings().get(2).isDiscard());
    }

@Test
    void reservedWordsMayNameMembersButRemainReservedLexically() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val mutex = SharedMutex.new(arr[1, 2, 3]);
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  fnc main(): void {
                    val new = 1;
                    return;
                  }
                end
                """));
    }

    @Test
    void stopDoAndDoneAreReservedButMayNameCallables() {
        var tokens = new Lexer("stop do done").scan();
        assertEquals(Token.Type.STOP, tokens.get(0).type());
        assertEquals(Token.Type.DO, tokens.get(1).type());
        assertEquals(Token.Type.DONE, tokens.get(2).type());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  fnc stop(): int { return 1; }
                  fnc do(): int { return 2; }
                  routine done(): int { return 3; }

                  fnc total(): int {
                    return stop() + do() + done();
                  }
                end
                """)));

        assertDoesNotThrow(() -> Parser.parse("""
                import fnc {stop, do, done} from './flow';

                define module app
                  fnc main(): void { return; }
                end
                """));

        assertDoesNotThrow(() -> Parser.parse("""
                define class Flow as
                  pub stop(): int { return 1; }
                  pub do(): int { return 2; }
                  pub done(): int { return 3; }
                end

                define interface FlowApi
                  fnc stop() => int;
                  fnc do() => int;
                  fnc done() => int;
                end
                """));
    }

    @Test
    void stopDoAndDoneCannotBeUsedAsOrdinaryIdentifiers() {
        for (String keyword : List.of("stop", "do", "done")) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc main(): void {
                        val %s = 1;
                        return;
                      }
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc take(int %s): void { return; }
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class %s as
                    end
                    """.formatted(keyword)));

            assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define module app
                      fnc %s(): int { return 1; }
                      fnc main(): void {
                        val callback = %s;
                        return;
                      }
                    end
                    """.formatted(keyword, keyword)));

            if (!keyword.equals("done")) assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                    define class Flow as
                      pub %s(): int { return 1; }
                    end
                    define module app
                      fnc main(): void {
                        val flow = new Flow();
                        val callback = flow.%s;
                        return;
                      }
                    end
                    """.formatted(keyword, keyword)));
        }
    }

    @Test
    void doneIsReadableAsAProtocolMemberButIsNotALexicalIdentifier() {
        assertDoesNotThrow(() -> Parser.parse("fnc check(IteratorResult<int> result): bool { return result.done; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc bad(): void { val done = true; return; }"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("fnc bad(): bool { return done; }"));
    }

    @Test
    void declarationNestingAllowsClassesInsideModulesButNotModulesOrClassesInsideClasses() {
        assertDoesNotThrow(() -> Parser.parse("""
                define module Accounts as
                  define class Account as
                    pub let int balance = 100;
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module Outer as
                  define module Inner as
                    fnc value(): int { return 1; }
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Outer as
                  define class Inner as
                  end
                end
                """));
    }

    @Test
    void classDeclarationsRequireAsDelimiter() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class CounterState as
                  pub let int value = 10;
                end
                """));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class CounterState
                          pub let int value = 10;
                        end
                        """));

        assertTrue(failure.getMessage().contains("expected 'as' after class header"));
    }

    private static List<List<String>> permutations(String... tokens) {
        List<List<String>> out = new ArrayList<>();
        permute(new ArrayList<>(List.of(tokens)), new ArrayList<>(), out);
        return out;
    }

    private static void permute(
            List<String> remaining,
            List<String> prefix,
            List<List<String>> out) {
        if (remaining.isEmpty()) {
            out.add(List.copyOf(prefix));
            return;
        }
        for (int index = 0; index < remaining.size(); index++) {
            List<String> nextRemaining = new ArrayList<>(remaining);
            String token = nextRemaining.remove(index);
            List<String> nextPrefix = new ArrayList<>(prefix);
            nextPrefix.add(token);
            permute(nextRemaining, nextPrefix, out);
        }
    }

}

package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StructuralTypingHardeningTest {
    @Test
    void canonicalStructuralKeywordAcceptsWidthSubtypingWithoutNominalImplements() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface PointLike
                    x: int;
                    y: int;
                  end

                  define class Point3d as
                    pub val int x = 1;
                    pub val int y = 2;
                    pub val int z = 3;
                  end

                  fnc sum(structural PointLike point): int {
                    return point.x + point.y;
                  }

                  fnc ok(): int {
                    return sum(new Point3d());
                  }
                end
                """)));
    }

    @Test
    void nominalParameterStillRequiresNominalRelationship() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface PointLike
                    x: int;
                  end

                  define class Point as
                    pub val int x = 1;
                  end

                  fnc take(PointLike point): int { return point.x; }
                  fnc bad(): int { return take(new Point()); }
                end
                """)));
    }

    @Test
    void classContractsCanBeUsedStructurallyAndOnlyPublicShapeCounts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Contract as
                    pub val int id = 1;
                    pub render(int value): String { return "ok"; }
                    private val int secret = 7;
                  end

                  define class Candidate as
                    pub val int id = 2;
                    pub val int extra = 3;
                    pub render(int value): String { return "candidate"; }
                  end

                  fnc use(structural Contract value): String {
                    return value.render(value.id);
                  }

                  fnc ok(): String { return use(new Candidate()); }
                end
                """)));
    }

    @Test
    void structuralMethodsCheckFullSignatureWithNominalVariance() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""

                define module app
                  define class Base as end
                  define class Derived extends Base as end

                  define interface Handler
                    fnc handle(Derived value) => Base;
                  end

                  define class Wider as
                    pub handle(Base value): Derived { return new Derived(); }
                  end

                  fnc invoke(structural Handler handler, Derived value): Base {
                    return handler.handle(value);
                  }

                  fnc ok(Derived value): Base {
                    return invoke(new Wider(), value);
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Handler
                    fnc handle(int value) => String;
                  end

                  define class Wrong as
                    pub handle(String value): int { return 1; }
                  end

                  fnc invoke(structural Handler handler): String {
                    return handler.handle(1);
                  }

                  fnc bad(): String { return invoke(new Wrong()); }
                end
                """)));
    }

    @Test
    void nominalInterfaceValuesCanFlowIntoStructuralSupercontracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Small
                    fnc run(int value) => int;
                  end

                  define interface Large
                    fnc run(int value) => int;
                    extra: int;
                  end

                  fnc use(structural Small value): int {
                    return value.run(1);
                  }

                  fnc ok(Large value): int {
                    return use(value);
                  }
                end
                """)));
    }

    @Test
    void structuralViewsCannotBecomeMutableAsyncOrActorMessages() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  fnc bad(structural Shape mut value): void { return; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  async fnc bad(structural Shape value): int { return value.value; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end
                  actor fnc bad(structural Shape value): void { return; }
                end
                """)));
    }

    @Test
    void actorIdentityCannotBeErasedByStructuralTyping() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  actor Worker {
                    pub run(): int { return 1; }
                  }

                  fnc bad(structural Worker worker): int {
                    return worker.run();
                  }
                end
                """)));
    }
    @Test
    void structuralCallableKeywordAndAnnotationNormalizeToOneAstFlag() {
        Ast.Program program = Parser.parse("""
                define module app
                  structural fnc by_keyword(): int { return 1; }

                  @structural
                  routine by_annotation(): int { return 2; }

                  define class Worker as
                    structural run(): int { return 3; }

                    @structural
                    pub static fnc build(): int { return 4; }
                  end

                  define interface Api
                    structural fnc call(int value) => int;
                  end
                end
                """);

        Ast.ModuleDecl module = program.modules().getFirst();

        Ast.FunctionDecl byKeyword = (Ast.FunctionDecl) module.declarations().get(0);
        Ast.FunctionDecl byAnnotation = (Ast.FunctionDecl) module.declarations().get(1);
        Ast.ClassDecl worker = (Ast.ClassDecl) module.declarations().get(2);
        Ast.InterfaceDecl api = (Ast.InterfaceDecl) module.declarations().get(3);

        assertTrue(byKeyword.structural());
        assertTrue(byAnnotation.structural());
        assertEquals(Ast.CallableKind.ROUTINE, byAnnotation.kind());
        assertTrue(byAnnotation.annotations().stream().noneMatch(a -> a.name().equalsIgnoreCase("structural")));

        assertTrue(worker.methods().get(0).structural());
        assertTrue(worker.methods().get(1).structural());
        assertTrue(worker.methods().get(1).annotations().stream().noneMatch(a -> a.name().equalsIgnoreCase("structural")));

        Ast.InterfaceFunctionDecl call = (Ast.InterfaceFunctionDecl) api.members().getFirst();
        assertTrue(call.structural());
    }

    @Test
    void structuralCallableMetadataIsSeparateFromStructuralParameterMatching() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define interface Shape
                    value: int;
                  end

                  define class Candidate as
                    pub val int value = 1;
                  end

                  structural fnc take(Shape value): int {
                    return value.value;
                  }

                  fnc bad(): int {
                    return take(new Candidate());
                  }
                end
                """)));
    }

    @Test
    void structuralCallableSyntaxRejectsDuplicatesArgumentsAndNonCallableTargets() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  @structural
                  structural fnc duplicate(): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  @structural(int)
                  fnc bad_annotation(): void { return; }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  define class Bad as
                    structural val int value = 1;
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  @structural
                  val int value = 1;
                end
                """));
    }

    @Test
    void structuralCallableMetadataDoesNotEraseActorIdentity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  actor Worker {
                    structural pub run(): int { return 1; }
                  }

                  @structural
                  actor fnc invoke(int value): int {
                    return value;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  actor Worker {
                    structural pub run(): int { return 1; }
                  }

                  fnc bad(structural Worker worker): int {
                    return worker.run();
                  }
                end
                """)));
    }

    @Test
    void structuralCallableContractsPreserveMutabilityModes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box as
                    pub val int value = 1;
                  end

                  define interface Mutating
                    fnc update(Box mut value) => void;
                  end

                  define class ReadOnlyCandidate as
                    pub update(Box value): void { return; }
                  end

                  fnc use(structural Mutating candidate): void {
                    return;
                  }

                  fnc bad(): void {
                    use(new ReadOnlyCandidate());
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box as
                    pub val int value = 1;
                  end

                  define interface ReadOnly
                    fnc update(Box value) => void;
                  end

                  define class MutatingCandidate as
                    pub update(Box mut value): void { return; }
                  end

                  fnc use(structural ReadOnly candidate): void {
                    return;
                  }

                  fnc bad(): void {
                    use(new MutatingCandidate());
                    return;
                  }
                end
                """)));
    }

    @Test
    void asyncCallableModeIsNotEquivalentToSyncFutureReturn() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  async fnc delayed(): int {
                    return 1;
                  }

                  fnc ok(): void {
                    val inferred = delayed;
                    return;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  async fnc delayed(): int {
                    return 1;
                  }

                  fnc bad(): void {
                    val Fnc<Future<int>> callback = delayed;
                    return;
                  }
                end
                """)));
    }

    @Test
    void contextualStructuralKeywordDoesNotStealOrdinaryMemberNames() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Naming as
                    pub structural(): int {
                      return 1;
                    }

                    pub structural<T>(T value): T {
                      return value;
                    }
                  end

                  fnc ok(): int {
                    val value = new Naming();
                    return value.structural();
                  }
                end
                """)));
    }

    @Test
    void lambdaMutabilityMustMatchItsContextualCallableContract() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module app
                  define class Box as
                    pub val int value = 1;
                  end

                  fnc bad(): void {
                    val Fnc<Box, void> callback = |Box mut value| -> {
                      return;
                    };
                    return;
                  }
                end
                """)));
    }

    @Test
    void asyncDeclarationIsNotAStructuralAliasForSyncFutureContract() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define module contracts
                  define interface Api
                    fnc load() => Future<int>;
                  end
                end

                @AdheresTo(contracts.Api)
                define module implementation
                  pub async fnc load(): int {
                    return 1;
                  }
                end
                """)));
    }

    @Test
    void unsupportedInterfaceCallableModifiersAreRejectedRatherThanDropped() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  define interface Bad
                    async fnc load() => Future<int>;
                  end
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  @structural
                  type Bad = int;
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define module app
                  define interface Bad
                    @RuntimeDecoration
                    fnc load() => int;
                  end
                end
                """));
    }

    @Test
    void structuralBorrowCannotEscapeIntoGeneratorActivation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define interface PointLike
                          x: int;
                        end
                        generator fnc suspended(structural PointLike point): int {
                          yield point.x;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("synchronous call"), failure.getMessage());
    }
}

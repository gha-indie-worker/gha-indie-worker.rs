package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorDeclarationLexicalBoundaryTest {

    @Test
    void defineActorAsParsesAtFileAndModuleScope() {
        Ast.Program program = Parser.parse("""
                define actor Worker as
                  pub receive(): void {
                    rt cooperate;
                    return;
                  }
                end

                define module services as
                  define isoactor Isolated as
                    pub process(): void { return; }
                  end

                  define untrusted actor Sandboxed as
                    pub run(): void { return; }
                  end
                end
                """);

        Ast.ClassDecl worker = (Ast.ClassDecl) program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(decl -> decl instanceof Ast.ClassDecl klass
                        && klass.name().equals("Worker"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.SHARED, worker.actorKind());

        Ast.ModuleDecl services = program.modules().stream()
                .filter(module -> module.name().equals("services"))
                .findFirst().orElseThrow();
        assertEquals(Ast.ActorKind.PRIVATE,
                ((Ast.ClassDecl) services.declarations().get(0)).actorKind());
        assertEquals(Ast.ActorKind.UNTRUSTED,
                ((Ast.ClassDecl) services.declarations().get(1)).actorKind());
    }

    @Test
    void defineActorStillForbidsConstructorsUntilRuntimeStartupLoweringExists() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define actor Worker as
                          pub constructor() {}
                        end
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }

    @Test
    void actorCannotCallEnclosingMainImplicitly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            main();
                            return;
                          }
                        end

                        pub routine main(): void { return; }
                        """)));
        assertTrue(failure.getMessage().contains("cannot implicitly access"), failure.getMessage());
    }

    @Test
    void ordinaryClassCanReadFileLevelCallable() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc helper(): int { return 42; }

                define class Box as
                  pub calculate(): int {
                    return helper();
                  }
                end
                """)));
    }

    @Test
    void ordinaryClassCanCaptureSameFileFunctionValue() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc helper(): int { return 42; }

                define class Box as
                  pub consume(): void {
                    val callback = helper;
                    callback();
                    return;
                  }
                end
                """)));
    }

    @Test
    void ActorMethodMayStillUseItsOwnMethodsAndBuiltins() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  pub ping(): void {
                    rt cooperate;
                    stdio.println("ping");
                    return;
                  }

                  pub run(): void {
                    self.ping();
                    return;
                  }
                end
                """)));
    }

    @Test
    void siblingModulesCanAccessEachOthersNamespacesInSameFile() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module internal as
                  pub fnc secret(): int { return 42; }
                end

                define module worker as
                  pub fnc run(): int {
                    return internal.secret();
                  }
                end
                """)));
    }

    @Test
    void moduleCanReadUniqueCallableFromSiblingModuleInSameFile() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module internal as
                  pub fnc secret(): int { return 42; }
                end

                define module worker as
                  pub fnc run(): int {
                    return secret();
                  }
                end
                """)));
    }

    @Test
    void moduleCanCallItsOwnMembersWithoutGlobalAccess() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module worker as
                  fnc local(): int { return 42; }
                  pub fnc run(): int { return local(); }
                end
                """)));
    }

    @Test
    void ordinaryClassCanUseSameFileModuleAndSiblingClassNamespaces() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module other as
                  pub fnc value(): int { return 9; }
                end

                define class Worker as
                  pub run(): int {
                    return other.value();
                  }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Helper as
                  pub static fnc value(): int { return 3; }
                end

                define class Worker as
                  pub run(): int {
                    return Helper.value();
                  }
                end
                """)));
    }

    @Test
    void ordinaryClassNonlexicalLambdaRetainsSameFileAmbientVisibility() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc globalHelper(): int { return 4; }

                define class Worker as
                  pub callback(): (() => int) {
                    return nlex || -> { return globalHelper(); };
                  }
                end
                """)));
    }

    @Test
    void actorCannotUseSameFileModuleClassOrTypeAlias() {
        IllegalArgumentException moduleFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module helpers as
                          pub fnc value(): int { return 1; }
                        end
                        define actor Worker as
                          pub run(): int { return helpers.value(); }
                        end
                        """)));
        assertTrue(moduleFailure.getMessage().contains("actor 'Worker'"), moduleFailure.getMessage());

        IllegalArgumentException classFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Helper as
                          pub static fnc value(): int { return 1; }
                        end
                        define actor Worker as
                          pub run(): int { return Helper.value(); }
                        end
                        """)));
        assertTrue(classFailure.getMessage().contains("actor 'Worker'"), classFailure.getMessage());

        IllegalArgumentException typeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        type Payload = int;
                        define actor Worker as
                          pub run(Payload payload): int { return payload; }
                        end
                        """)));
        assertTrue(typeFailure.getMessage().contains("same-file type alias 'Payload'"), typeFailure.getMessage());
    }

    @Test
    void ordinaryClassCanUseSameFileTypesWithoutImports() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Payload = int;

                define class Worker as
                  pub run(Payload payload): int { return payload; }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Payload as
                end

                define class Worker as
                  pub run(Payload payload): void { return; }
                end
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define interface Payload as
                end

                define class Worker as
                  pub run(Payload payload): void { return; }
                end
                """)));
    }

    @Test
    void moduleCanUseTypeFromSiblingModuleInSameFile() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define module model as
                  type Payload = int;
                end

                define module worker as
                  pub fnc run(Payload payload): int { return payload; }
                end
                """)));
    }

    @Test
    void actorMayUseExplicitSameFileParentButNotAmbientSiblingTypes() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                shared actor Parent {
                  pub fnc inherited(): int { return 1; }
                }

                shared actor Child extends Parent {
                  pub fnc current(): int { return self.inherited(); }
                }
                """)));

        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        isoactor Parent {
                        }

                        shared actor Child extends Parent {
                        }
                        """)));
        assertTrue(mismatch.getMessage().contains("isolation kind"),
                mismatch.getMessage());
    }


    @Test
    void actorLexicalBoundarySurvivesNonlexicalLambda() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 4; }

                        define actor Worker as
                          pub callback(): (() => int) {
                            return nlex || -> { return helper(); };
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("actor 'Worker'"), failure.getMessage());
    }

    @Test
    void lambdaSyntaxRequiresSlimArrow() {
        assertDoesNotThrow(() -> Parser.parse("""
                pub routine main(): void {
                  val create = || -> { return 42; };
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                pub routine main(): void {
                  val create = || => { return 42; };
                  return;
                }
                """));
    }


    @Test
    void actorOnStartIsReservedActorLocalZeroArgVoidHook() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(): void {
                    return;
                  }
                end
                """)));

        IllegalArgumentException publicHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub on_start(): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(publicHook.getMessage().contains("actor-local"), publicHook.getMessage());

        IllegalArgumentException parameterizedHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(int value): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(parameterizedHook.getMessage().contains("zero arguments"),
                parameterizedHook.getMessage());

        IllegalArgumentException nonVoidHook = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): int {
                            return 1;
                          }
                        end
                        """)));
        assertTrue(nonVoidHook.getMessage().contains("return void"),
                nonVoidHook.getMessage());
    }

    @Test
    void ordinaryClassCannotDeclareReservedActorOnStartHook() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                          on_start(): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("only be declared by an actor class"),
                failure.getMessage());
    }


    @Test
    void spawnActorTypechecksAsActorRefWithReadyDoneAndMailboxSend() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  on_start(): void {
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    return;
                  }

                  pub work(): void {
                    self.send("hello");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("start");
                  await worker.done;
                  return;
                }
                """)));
    }

    @Test
    void spawnRejectsOrdinaryClassesAndConstructorStyleArguments() {
        IllegalArgumentException ordinary = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          return;
                        }
                        """)));
        assertTrue(ordinary.getMessage().contains("spawn requires an actor class"),
                ordinary.getMessage());

        IllegalArgumentException arguments = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker(1);
                          return;
                        }
                        """)));
        assertTrue(arguments.getMessage().contains("does not accept constructor/startup arguments"),
                arguments.getMessage());
    }

    @Test
    void actorTransportMembersAreReservedAndMailboxRejectsClosures() {
        IllegalArgumentException overrideSend = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub send(String value): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(overrideSend.getMessage().contains("reserved"),
                overrideSend.getMessage());

        IllegalArgumentException functionPayload = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(|| -> { return 1; });
                          return;
                        }
                        """)));
        assertTrue(functionPayload.getMessage().contains("concrete owned/sendable"),
                functionPayload.getMessage());
    }

    @Test
    void onStartCannotBeInvokedDirectlyFromActorCode() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          on_start(): void {
                            return;
                          }

                          pub run(): void {
                            self.on_start();
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("cannot be called directly"),
                failure.getMessage());
    }


    @Test
    void selfImportEscapeHatchIsRejected() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        import fnc helper from "@self";
                        fnc helper(): int { return 1; }
                        """)));
        assertTrue(failure.getMessage().contains("cannot import itself"), failure.getMessage());
    }





    @Test
    void actorEndAndEndWithCleanupAreDistinctNonOverridableApis() {
        IllegalArgumentException overloadedEnd = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.end(|| -> { return; });
                            return;
                          }
                        end
                        """)));
        assertTrue(
                overloadedEnd.getMessage().contains("use self.endWithCleanup"),
                overloadedEnd.getMessage());

        IllegalArgumentException missingCleanup = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.endWithCleanup();
                            return;
                          }
                        end
                        """)));
        assertTrue(
                missingCleanup.getMessage().contains("exactly one"),
                missingCleanup.getMessage());

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.endWithCleanup(|| -> { return; });
                    return;
                  }
                end
                """)));

        IllegalArgumentException override = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          pub endWithCleanup(): void { return; }
                          receive(ActorMail<String> mail): void { return; }
                        end
                        """)));
        assertTrue(
                override.getMessage().contains("reserved"),
                override.getMessage());
    }


    @Test
    void actorMailboxRejectsMutableClassInstancesBeforeRuntimeTransport() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Payload as
                          val int value = 1;
                        end

                        define actor Worker as
                          receive(ActorMail<Object> mail): void {
                            self.end();
                            return;
                          }
                        end

                        pub routine main(): void {
                          val worker = spawn Worker();
                          worker.send(new Payload());
                          return;
                        }
                        """)));
        assertTrue(
                failure.getMessage().contains("cannot transport class instance 'Payload' by value"),
                failure.getMessage());
    }

    @Test
    void actorCannotPublishItsMutableSelfAsData() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            self.send(self);
                            self.end();
                            return;
                          }
                        end
                        """)));
        assertTrue(
                failure.getMessage().contains("concrete owned/sendable")
                        || failure.getMessage().contains("actor instance"),
                failure.getMessage());
    }


    @Test
    void ordinaryClassBoundaryIncludesStaticFunctionsAndFieldInitializers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc helper(): int { return 7; }

                define class Worker as
                  val int value = helper();

                  pub static fnc calculate(): int {
                    return helper();
                  }
                end
                """)));
    }

    @Test
    void ordinaryClassAndModuleCanReadTheirNormalSameFileLexicalEnvironment() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc rootHelper(): int { return 5; }

                define module services as
                  fnc localHelper(): int { return rootHelper(); }

                  define class Worker as
                    pub calculate(): int {
                      return localHelper() + rootHelper();
                    }
                  end

                  pub fnc run(): int {
                    return rootHelper();
                  }
                end
                """)));
    }

    @Test
    void actorInsideModuleCannotReadItsContainingModuleCallableImplicitly() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define module services as
                          fnc helper(): int { return 7; }

                          define actor Worker as
                            pub calculate(): int {
                              return helper();
                            }
                          end
                        end
                        """)));
        assertTrue(failure.getMessage().contains("actor 'Worker'"),
                failure.getMessage());
    }


    @Test
    void actorBoundaryAlsoAppliesToFieldInitializersAndStaticFunctions() {
        IllegalArgumentException fieldInitializer = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 7; }

                        define actor Worker as
                          let int value = helper();

                          receive(ActorMail<String> mail): void {
                            self.end();
                            return;
                          }
                        end
                        """)));
        assertTrue(fieldInitializer.getMessage().contains("actor 'Worker'"),
                fieldInitializer.getMessage());

        IllegalArgumentException staticFunction = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc helper(): int { return 7; }

                        define actor Worker as
                          pub static fnc calculate(): int {
                            return helper();
                          }
                        end
                        """)));
        assertTrue(staticFunction.getMessage().contains("actor 'Worker'"),
                staticFunction.getMessage());
    }

    @Test
    void actorBoundaryRejectsSameFileInterfacesInSignatures() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define interface Payload as
                          fnc id(): int;
                        end

                        define actor Worker as
                          pub consume(Payload payload): void {
                            return;
                          }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("same-file interface 'Payload'"),
                failure.getMessage());
    }

}

package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class ModulesNamespacesCallableSemanticsTest {

    @Test
    void namespaceModulesRoutineFncAndLambdaWorkTogether() throws Exception {
        String output = run("""
                namespace callable_demo;

                type IntFn = typeof fnc(int value) => int;

                define module math
                  pub fnc factorial(int n): int {
                    if n <= 1; do
                      return 1;
                    else
                      return n * factorial(n - 1);
                    fi
                  }
                end

                define module closures
                  pub fnc makeAdder(int base): IntFn {
                    return |value| -> {
                      return base + value;
                    };
                  }
                end

                pub routine main(): void {
                  val IntFn addTen = closures.makeAdder(10);
                  stdio.stdout.write(math.factorial(5));
                  stdio.stdout.write("|");
                  stdio.stdout.write(addTen(7));
                  return;
                }
                """);

        assertEquals("120|17", output);
    }

    @Test
    void routineAndFncMayBothRecurse() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                routine boot(bool finished): void {
                  if finished; do
                    return;
                  else
                    boot(true);
                    return;
                  fi
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc countdown(int n): int {
                  if n <= 0; do
                    return 0;
                  else
                    return countdown(n - 1);
                  fi
                }
                """)));
    }

    @Test
    void lambdaIsLexicalAndAnonymous() throws Exception {
        String output = run("""
                type IntFn = typeof fnc(int value) => int;

                fnc makeAdder(int base): IntFn {
                  return |value| -> {
                    return base + value;
                  };
                }

                pub routine main(): void {
                  val IntFn addTwo = makeAdder(2);
                  val IntFn addForty = makeAdder(40);
                  stdio.stdout.write(addTwo(5));
                  stdio.stdout.write("|");
                  stdio.stdout.write(addForty(2));
                  return;
                }
                """);

        assertEquals("7|42", output);
    }

    @Test
    void modulesConformToDedicatedContracts() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define contract MathApi as
                  fnc add(int a, int b) => int;
                  String name;
                end

                define module math conforms MathApi as
                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                  pub val String name = "math";
                end
                """)));
    }

    @Test
    void moduleContractsCannotBeUsedAsTypeInterfacesOrTraits() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract MathApi as
                  fnc add(int a, int b) => int;
                end

                define class Bad implements MathApi as
                  add(int a, int b): int {
                    return a + b;
                  }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract MathApi as
                  fnc add(int a, int b) => int;
                end

                define module math conforms MathApi as
                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                end

                fnc bad(): MathApi {
                  return math;
                }
                """)));
    }

    @Test
    void legacyModuleAdheresToIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract MathApi as
                  fnc add(int a, int b) => int;
                end

                @AdheresTo(MathApi)
                define module math as
                  pub fnc add(int a, int b): int {
                    return a + b;
                  }
                end
                """)));
    }

    @Test
    void moduleContractFieldsRequireExactBindingSemantics() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define contract Config as
                  val String name;
                  let int capacity;
                end

                define module good conforms Config as
                  pub val String name = "x";
                  pub let int capacity = 10;
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract Config as
                  val String name;
                end

                define module bad conforms Config as
                  pub let String name = "x";
                end
                """)));
    }

    @Test
    void moduleContractInheritanceIsContractOnly() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define contract Base as
                  val String name;
                end

                define contract Derived extends Base as
                  fnc size(): int;
                end

                define module good conforms Derived as
                  pub val String name = "x";
                  pub fnc size(): int { return 1; }
                end
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define interface Base as
                  fnc size(): int;
                end

                define contract Bad extends Base as
                  fnc size(): int;
                end
                """)));
    }

    @Test
    void moduleContractsDoNotHaveSelfTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract Bad as
                  fnc clone(): self;
                end
                """)));
    }

    @Test
    void publicModuleContractsAreLegalDeclarations() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                pub define contract PublicApi as
                  fnc ping(): void;
                end
                """)));
    }

    @Test
    void moduleContractsCannotLeakThroughNominalAssignability() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract Api as
                  fnc ping(): void;
                end

                define class User as
                  pub fnc ping(): void { return; }
                end

                fnc bad(): Api {
                  return User();
                }
                """)));
    }

    @Test
    void duplicateModuleContractsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define contract Api as
                  fnc ping(): void;
                end

                define module m conforms Api, Api as
                  pub fnc ping(): void { return; }
                end
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "modules-namespaces-callables.ores")
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

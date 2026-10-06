package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ClassConstructorHardeningTest {

    @TempDir
    Path temp;

    @Test
    void constructorIsAFirstClassClassMemberWithModuleVisibility() {
        Ast.Program program = Parser.parse("""
                define module model as
                  pub define class Box as
                    val int value;

                    pub constructor(int value) {
                      self.value = value;
                    }

                    pub get(): int {
                      return self.value;
                    }
                  end
                end
                """);

        Ast.ModuleDecl module = program.modules().stream()
                .filter(candidate -> candidate.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl box = (Ast.ClassDecl) module.declarations().getFirst();

        assertEquals(Ast.Visibility.PUBLIC, box.visibility());
        assertNotNull(box.constructor());
        assertEquals(Ast.Visibility.PUBLIC, box.constructor().visibility());
        assertEquals(1, box.constructor().arity());
        assertEquals("value", box.constructor().parameters().getFirst().name());
    }

    @Test
    void explicitConstructorInitializesImmutableStorageExactlyOnce() throws Exception {
        String output = run("""
                define class Box as
                  val int value;

                  constructor(int value) {
                    self.value = value;
                  }

                  pub get(): int {
                    return self.value;
                  }
                end

                pub routine main(): void {
                  val box = new Box(42);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void omittedConstructorKeepsLegacySynthesizedFieldInitialization() throws Exception {
        String output = run("""
                define class Legacy as
                  val int value;
                  val int offset = 2;

                  pub total(): int {
                    return self.value + self.offset;
                  }
                end

                pub routine main(): void {
                  val legacy = new Legacy(40);
                  stdio.stdout.write(legacy.total());
                  return;
                }
                """);

        assertEquals("42", output);
    }

    @Test
    void parserRejectsJavaStyleDuplicateNestedAndTypedConstructors() {
        IllegalArgumentException javaStyle = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          Box(int value) {
                            return;
                          }
                        end
                        """));
        assertTrue(javaStyle.getMessage().contains("constructor(...)"));
        assertTrue(javaStyle.getMessage().contains("not the class name"));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          constructor() { return; }
                          constructor(int value) { return; }
                        end
                        """));
        assertTrue(duplicate.getMessage().contains("only one constructor"));

        IllegalArgumentException typed = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Box as
                          constructor(): void {
                            return;
                          }
                        end
                        """));
        assertTrue(typed.getMessage().contains("do not declare a return type"));

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define class Outer as
                          define class Inner as
                          end
                        end
                        """));
        assertTrue(nested.getMessage().contains("cannot be nested inside classes"));
    }

    @Test
    void topLevelClassesStayFilePrivateAndModuleVisibilityIsExplicit() {
        IllegalArgumentException topLevelPublic = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        pub define class Exported as
                        end
                        """));
        assertTrue(topLevelPublic.getMessage().contains("top-level classes are file-private"));

        IllegalArgumentException publicConstructorOnPrivateClass = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        define module model as
                          define class Hidden as
                            pub constructor() { return; }
                          end
                        end
                        """));
        assertTrue(publicConstructorOnPrivateClass.getMessage().contains("public constructor requires a public module class"));

        Ast.Program program = Parser.parse("""
                define module model as
                  define class Hidden as
                  end

                  pub define class Exported as
                    private constructor() { return; }
                  end
                end
                """);
        Ast.ModuleDecl model = program.modules().stream()
                .filter(module -> module.name().equals("model"))
                .findFirst()
                .orElseThrow();
        Ast.ClassDecl hidden = (Ast.ClassDecl) model.declarations().get(0);
        Ast.ClassDecl exported = (Ast.ClassDecl) model.declarations().get(1);
        assertEquals(Ast.Visibility.PRIVATE, hidden.visibility());
        assertEquals(Ast.Visibility.PUBLIC, exported.visibility());
        assertEquals(Ast.Visibility.PRIVATE, exported.constructor().visibility());
    }

    @Test
    void constructorMustInitializeEveryRequiredFieldAndCannotRewriteImmutableFields() {
        PolyglotException missing = assertThrows(
                PolyglotException.class,
                () -> run("""
                        define class Broken as
                          val int value;
                          constructor() {
                            return;
                          }
                        end

                        pub routine main(): void {
                          val broken = new Broken();
                          return;
                        }
                        """));
        assertTrue(missing.getMessage().contains("did not initialize field 'value'"));

        PolyglotException twice = assertThrows(
                PolyglotException.class,
                () -> run("""
                        define class Broken as
                          val int value;
                          constructor(int value) {
                            self.value = value;
                            self.value = value + 1;
                          }
                        end

                        pub routine main(): void {
                          val broken = new Broken(1);
                          return;
                        }
                        """));
        assertTrue(twice.getMessage().contains("immutable"));
    }

    @Test
    void explicitParentConstructorsRequireFutureSuperChaining() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Parent as
                          constructor() {
                            return;
                          }
                        end

                        define class Child extends Parent as
                        end
                        """)));
        assertTrue(failure.getMessage().contains("super-constructor chaining"));
    }

    @Test
    void crossFileConstructionRequiresPublicModuleClassAndPublicConstructor() throws Exception {
        Path library = temp.resolve("library.ores");
        Files.writeString(library, """
                define module model as
                  pub define class PublicBox as
                    val int value;

                    pub constructor(int value) {
                      self.value = value;
                    }

                    pub get(): int {
                      return self.value;
                    }
                  end

                  pub define class FactoryOnly as
                    val int value = 1;

                    private constructor() {
                      return;
                    }
                  end

                  define class HiddenBox as
                    val int value = 2;
                  end
                end
                """);

        Path publicMain = temp.resolve("public-main.ores");
        Files.writeString(publicMain, """
                import class PublicBox from "./library.ores";

                pub routine main(): void {
                  val box = new PublicBox(42);
                  stdio.stdout.write(box.get());
                  return;
                }
                """);
        assertEquals("42", runLinked(publicMain));

        Path privateConstructorMain = temp.resolve("private-constructor-main.ores");
        Files.writeString(privateConstructorMain, """
                import class FactoryOnly from "./library.ores";

                pub routine main(): void {
                  val value = new FactoryOnly();
                  return;
                }
                """);
        Exception privateConstructor = assertThrows(
                Exception.class,
                () -> runLinked(privateConstructorMain));
        assertTrue(privateConstructor.getMessage().contains("constructor")
                || (privateConstructor.getCause() != null
                    && String.valueOf(privateConstructor.getCause().getMessage()).contains("constructor")));

        Path privateClassMain = temp.resolve("private-class-main.ores");
        Files.writeString(privateClassMain, """
                import class HiddenBox from "./library.ores";

                pub routine main(): void {
                  val value = new HiddenBox();
                  return;
                }
                """);
        Exception privateClass = assertThrows(
                Exception.class,
                () -> runLinked(privateClassMain));
        assertTrue(privateClass.getMessage().contains("public ordinary class")
                || privateClass.getMessage().contains("export")
                || (privateClass.getCause() != null
                    && String.valueOf(privateClass.getCause().getMessage()).contains("export")));
    }

    @Test
    void actorsRejectClassConstructors() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse("""
                        actor Worker {
                          constructor() {
                            return;
                          }
                        }
                        """));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "class-constructor-hardening.ores")
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

    private static String runLinked(Path entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LinkedProgramRunner.run(
                entry,
                IsolatePolicy.developer(),
                ExecutionProfile.serverJit(),
                Set.of(),
                Map.of(),
                output,
                new ByteArrayOutputStream());
        return output.toString(StandardCharsets.UTF_8);
    }
}

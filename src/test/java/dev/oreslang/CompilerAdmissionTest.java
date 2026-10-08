package dev.oreslang;

import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CompilerAdmissionTest {
    private static final String INVALID_OWNERSHIP = """
            define class Box as
              pub let int value = 0;
            end
            fnc change(Box box): void {
              box.value = 1;
              return;
            }
            pub routine main(): void { return; }
            """;

    @Test
    void typeAnalysisAndExecutableAdmissionHaveSeparateResponsibilities() {
        assertDoesNotThrow(() -> TypeChecker.checkTypes(Parser.parse(INVALID_OWNERSHIP)));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(Parser.parse(INVALID_OWNERSHIP)));
    }

    @Test
    void everyAdmissionEntrypointRejectsOwnershipViolationsIncludingUnreachableCode() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(INVALID_OWNERSHIP)));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(INVALID_OWNERSHIP));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.compileForBuild(INVALID_OWNERSHIP, BuildOptions.executable()));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.validateForIsolate(INVALID_OWNERSHIP, IsolatePolicy.developer()));
    }
}

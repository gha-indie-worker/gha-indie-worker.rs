package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class SharedNativeDeferSourceParityTest {
    @Test
    void sharedBorrowCannotEscalateToMutableReborrow() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int value = 0;
                        end
                        fnc mutate(borrow mut Counter c): void {
                          c.value = 2;
                          return;
                        }
                        fnc invalid(borrow Counter c): void {
                          mutate(rt borrow mut c);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("cannot mutably reborrow a shared borrow"),
                failure::getMessage);
    }

    @Test
    void exactNativeLifoSourceChecksAndExecutesOnGraalVM() throws Exception {
        Path fixture = Path.of("contracts/v1/semantics/fixtures/native-defer-lifo-cfg.ores");
        String source = Files.readString(fixture, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                "shared native defer fixture must be type-admitted by Java/GraalVM");
        try (Context context = Context.newBuilder("ores").allowAllAccess(true).build()) {
            Value result = context.eval(Source.newBuilder("ores", source, fixture.toString()).build());
            assertTrue(result.fitsInLong(), "shared native fixture must return an integer");
            assertEquals(111L, result.asLong(), "same two LIFO cleanup paths as native Clang");
        }
    }
}

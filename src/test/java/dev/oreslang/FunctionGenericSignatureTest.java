package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class FunctionGenericSignatureTest {
    @Test
    void signatureInsideGenericsMatchesLegacyCommaForm() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type SignatureHandler = Fnc<String(int, bool)>;
                type CommaHandler = Fnc<int, bool, String>;

                fnc make(): SignatureHandler {
                  return |int value, bool enabled| -> {
                    return enabled ? "enabled" : "disabled";
                  };
                }

                fnc consume(CommaHandler callback): String {
                  return callback(7, true);
                }

                fnc bridge(): String {
                  val SignatureHandler callback = make();
                  return consume(callback);
                }
                """)));
    }

    @Test
    void functionAliasSupportsSignatureAndCommaForms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc stringify(int value): String {
                  return "value";
                }

                fnc apply(Function<String(int)> callback, int value): String {
                  return callback(value);
                }

                fnc bridge(): String {
                  val Function<int, String> legacy = stringify;
                  return apply(legacy, 42);
                }
                """)));
    }

    @Test
    void signatureFormSupportsZeroArgumentsAndDocumentationNames() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc use(): String {
                  val Fnc<int()> answer = || -> {
                    return 42;
                  };
                  val Fnc<String(int value, bool enabled)> describe = |int value, bool enabled| -> {
                    return enabled ? "yes" : "no";
                  };
                  answer();
                  return describe(1, true);
                }
                """)));
    }

    @Test
    void signatureFormSupportsNestedCallableResults() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type IntFormatter = Fnc<String(int)>;
                type FormatterFactory = Fnc<IntFormatter(bool)>;

                fnc makeFormatter(bool enabled): IntFormatter {
                  return |int value| -> {
                    return enabled ? "on" : "off";
                  };
                }

                fnc use(): FormatterFactory {
                  return |bool enabled| -> {
                    return makeFormatter(enabled);
                  };
                }
                """)));
    }

    @Test
    void signatureFormStillChecksParameterAndResultTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc badResult(): void {
                  val Fnc<String(int)> callback = |int value| -> {
                    return value;
                  };
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc badParameter(): void {
                  val Fnc<String(int)> callback = |String value| -> {
                    return value;
                  };
                  return;
                }
                """)));
    }
}

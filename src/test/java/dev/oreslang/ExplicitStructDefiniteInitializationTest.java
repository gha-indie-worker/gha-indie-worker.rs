package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ExplicitStructDefiniteInitializationTest {
    @Test
    void oneIfPathCannotEstablishInitialization() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  if choose; then
                    point.ready = true;
                  fi
                  return point.ready;
                }
                """)));
    }

    @Test
    void allIfPathsCanEstablishInitialization() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  if choose; then
                    point.ready = true;
                  else
                    point.ready = false;
                  fi
                  return point.ready;
                }
                """)));
    }

    @Test
    void loopBodyCannotEstablishInitializationAfterLoop() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  for const item of arr[1] do
                    point.ready = true;
                  done
                  return point.ready;
                }
                """)));
    }

    @Test
    void ternaryRequiresInitializationOnBothAlternatives() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const ignored = choose ? (point.ready = true) : false;
                  return point.ready;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(bool choose): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const ignored = choose ? (point.ready = true) : (point.ready = false);
                  return point.ready;
                }
                """)));
    }
    @Test
    void lambdaBodyCannotEstablishInitializationUntilItRuns() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  const initialize = || -> {
                    point.ready = true;
                    return;
                  };
                  return point.ready;
                }
                """)));
    }

    @Test
    void deferCannotEstablishInitializationForFollowingCode() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): bool {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  defer point.ready = true;
                  return point.ready;
                }
                """)));
    }

    @Test
    void lexicalLambdaCannotCapturePartialExplicitStruct() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): (() => bool) {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  return || -> {
                    point.ready = true;
                    return point.ready;
                  };
                }
                """)));
    }

    @Test
    void lexicalLambdaMayCaptureExplicitStructAfterCompletion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc ok(): (() => bool) {
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  point.ready = true;
                  return || -> point.ready;
                }
                """)));
    }

    @Test
    void nonblockingSelectCannotCapturePartialExplicitStruct() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  nb select {
                    case readch input: val value {
                      stdio.println(point.foo);
                    }
                  }
                  point.ready = true;
                  return;
                }
                """)));
    }

    @Test
    void nonblockingSelectMayCaptureExplicitStructAfterCompletion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc ok(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  point.ready = true;
                  nb select {
                    case readch input: val value {
                      stdio.println(point.foo);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void nonblockingWriteCallbackCannotCapturePartialExplicitStruct() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  nb cb writech output, 42 || -> {
                    stdio.println(point.foo);
                  };
                  point.ready = true;
                  return;
                }
                """)));
    }

    @Test
    void nonblockingWriteCallbackMayCaptureExplicitStructAfterCompletion() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc ok(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  const mut point = struct{foo: string, ready: bool}{foo: "before"};
                  point.ready = true;
                  nb cb writech output, 42 || -> {
                    stdio.println(point.foo);
                  };
                  return;
                }
                """)));
    }

}

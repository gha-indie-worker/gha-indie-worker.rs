package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Select ownership is about overlapping continuation lifetimes, not the
 * number of carrier threads running actor code.
 */
final class SelectBorrowLifetimeTest {
    private static final String BAR = """
            define class Bar as
              pub let String foo = "start";
            end
            """;

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    private static void accept(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void blockingStaticSelectCannotSuspendWithOutstandingBorrow() {
        reject(BAR + """
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  select {
                    case readch ch: val received {
                      stdio.println(received);
                    }
                  }
                  stdio.println(view.foo);
                  return;
                }
                """, "cannot select while an ordinary borrow is live");
    }

    @Test
    void blockingReadchCannotSuspendWithOutstandingBorrow() {
        reject(BAR + """
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  val int received = readch ch;
                  stdio.println(view.foo);
                  return;
                }
                """, "cannot readch/writech while an ordinary borrow is live");
    }

    @Test
    void blockingWritechCannotSuspendWithOutstandingBorrow() {
        reject(BAR + """
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(0);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  writech ch, 12;
                  stdio.println(view.foo);
                  return;
                }
                """, "cannot readch/writech while an ordinary borrow is live");
    }

    @Test
    void blockingDynamicSelectCannotSuspendWithOutstandingBorrow() {
        reject(BAR + """
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Array<SelectCase> cases = [SelectCase.read(ch)];
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  val Option<SelectResult> choice = select from cases;
                  stdio.println(view.foo);
                  return;
                }
                """, "cannot select from cases while an ordinary borrow is live");
    }

    @Test
    void immediateSelectMayRunWithAnOrdinaryLexicalBorrow() {
        accept(BAR + """
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  try select {
                    case readch ch: val received {
                      stdio.println(view.foo);
                    }
                    default: {
                      stdio.println(view.foo);
                    }
                  }
                  return;
                }
                """);
    }

    @Test
    void blockingSelectWithDefaultCannotSuspendSoBorrowRemainsValid() {
        accept(BAR + """
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  select {
                    case readch ch: val received {
                      stdio.println(view.foo);
                    }
                    default: {
                      stdio.println(view.foo);
                    }
                  }
                  return;
                }
                """);
    }

    @Test
    void nonblockingSelectRejectsBorrowedCapturedValue() {
        reject(BAR + """
                actor fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  nb select {
                    case readch ch: val received {
                      stdio.println(view.foo);
                    }
                  }
                  return;
                }
                """, "nb select continuation cannot capture borrowed value 'view'");
    }

    @Test
    void nonblockingSelectTransfersOwnedCaptureOnRegistration() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val Array<int> target = [1, 2];
                  nb select {
                    case readch ch: val received {
                      stdio.println(target[0]);
                    }
                  }
                  stdio.println(target[0]);
                  return;
                }
                """, "moved value");
    }

    @Test
    void nonblockingSelectCanCopyImmutableValuesWithoutMovingOwner() {
        accept("""
                actor fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val int value = 7;
                  nb select {
                    case readch ch: val received {
                      stdio.println(value + received);
                    }
                  }
                  stdio.println(value);
                  return;
                }
                """);
    }

    @Test
    void nonblockingSelectDoesNotCaptureShadowedOuterBinding() {
        accept("""
                actor fnc good(): void {
                  val Channel<Array<int>> ch = Channel.new<Array<int>>(1);
                  val Array<int> value = [7, 8];
                  nb select {
                    case readch ch: val value {
                      stdio.println(value[0]);
                    }
                  }
                  stdio.println(value[0]);
                  return;
                }
                """);
    }

    @Test
    void nonblockingRegistrationMayCoexistWithUncapturedLexicalBorrow() {
        accept(BAR + """
                actor fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  nb select {
                    case readch ch: val received {
                      stdio.println(received);
                    }
                  }
                  stdio.println(view.foo);
                  return;
                }
                """);
    }

    @Test
    void nonblockingReadchIsNotItselfASuspension() {
        accept(BAR + """
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  let Bar target = new Bar();
                  val &Bar view = rt borrow target;
                  val Future<int> received = nb readch ch;
                  stdio.println(view.foo);
                  return;
                }
                """);
    }
}

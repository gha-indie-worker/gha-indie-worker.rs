package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A callback armed for a later mailbox turn owns its captured values at registration. */
final class DeferredChannelOwnershipHardeningTest {
    private static final String BAR = """
            define class Bar as
              pub let String foo = "start";
            end
            """;

    private static void accept(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test
    void callbackMovesCapturedOwnedObjectAtRegistration() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  val Array<int> data = [7, 8];
                  nb cb writech output, 42 || -> {
                    stdio.println(data[0]);
                  };
                  stdio.println(data[0]);
                  return;
                }
                """, "moved value");
    }

    @Test
    void callbackRejectsBorrowedCapturedValue() {
        reject(BAR + """
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  let Bar owner = new Bar();
                  val &Bar view = rt borrow owner;
                  nb cb writech output, 42 || -> {
                    stdio.println(view.foo);
                  };
                  return;
                }
                """, "cannot capture borrowed value 'view'");
    }

    @Test
    void callbackCanCaptureCopyValueAndContinueUsingIt() {
        accept("""
                actor fnc good(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  val int value = 7;
                  nb cb writech output, 42 || -> {
                    stdio.println(value);
                  };
                  stdio.println(value);
                  return;
                }
                """);
    }

    @Test
    void callbackDoesNotStealUncapturedBorrow() {
        accept(BAR + """
                actor fnc good(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  let Bar owner = new Bar();
                  val &Bar view = rt borrow owner;
                  nb cb writech output, 42 || -> {
                    stdio.println("done");
                  };
                  stdio.println(view.foo);
                  return;
                }
                """);
    }

    @Test
    void callbackRejectsMutationOfOuterCopyVariableAfterRegistration() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  let int value = 2;
                  nb cb writech output, 42 || -> {
                    value = value + 1;
                  };
                  stdio.println(value);
                  return;
                }
                """, "moved value");
    }

    @Test
    void directActorSelfMayReenterThroughCallback() {
        accept("""
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    val Channel<int> output = Channel.new<int>(1);
                    nb cb writech output, 1 || -> {
                      self.end();
                    };
                    return;
                  }
                end
                """);
    }

    @Test
    void actorSelfBorrowAliasCannotEscapeThroughCallback() {
        reject("""
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    val Channel<int> output = Channel.new<int>(1);
                    val alias = rt borrow self;
                    nb cb writech output, 1 || -> {
                      stdio.println(alias);
                    };
                    return;
                  }
                end
                """, "cannot capture borrowed value 'alias'");
    }

    @Test
    void actorSelfBorrowAliasCannotEscapeThroughNbSelect() {
        reject("""
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    val Channel<int> input = Channel.new<int>(1);
                    val alias = rt borrow self;
                    nb select {
                      case readch input: val received {
                        stdio.println(alias);
                      }
                    }
                    return;
                  }
                end
                """, "cannot capture borrowed value 'alias'");
    }

    @Test
    void outerNbSelectTransfersCapturesUsedInsideNestedWriteCallback() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val Channel<int> output = Channel.new<int>(1);
                  val Array<int> data = [7, 8];
                  nb select {
                    case readch input: val received {
                      nb cb writech output, received || -> {
                        stdio.println(data[0]);
                      };
                    }
                  }
                  stdio.println(data[0]);
                  return;
                }
                """, "moved value");
    }

    @Test
    void callbackCannotCaptureGuardBearingValue() {
        reject("""
                actor fnc bad(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  val Mutex<int> mutex = Mutex.new(3);
                  val guard = mutex.lock();
                  nb cb writech output, 42 || -> {
                    stdio.println(guard.is_released());
                  };
                  return;
                }
                """, "MutexGuard");
    }
}

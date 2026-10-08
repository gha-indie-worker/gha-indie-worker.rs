package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class AsyncChannelBoundaryTest {
    @Test
    void asyncFunctionsCanOwnDataOnlyChannelHandles() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc consume(Channel<Option<List<int>>> input): int {
                  val Future<Option<List<int>>> pending = nb readch input;
                  val Option<List<int>> next = await pending;
                  if next.is_none() then
                    return 0;
                  fi
                  return next.unwrap().length;
                }
                """)));
    }

    @Test
    void channelChunkLengthRemainsNumericForBoundsChecks() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                async fnc remaining(Channel<Option<List<int>>> input): int {
                  val Option<List<int>> frame = await (nb readch input);
                  if frame.is_none() then
                    return 0;
                  fi
                  val List<int> bytes = frame.unwrap();
                  val int remaining = 256 - bytes.length;
                  if bytes.length > 256 then
                    return -1;
                  fi
                  return remaining;
                }
                """)));
    }

    @Test
    void asyncChannelsRejectCallablePayloadsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Channel<Fnc<int, int>> input): void {
                  return;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Channel<Option<Fnc<int, int>>> input): void {
                  return;
                }
                """)));
    }

    @Test
    void unrelatedUnsafeAsyncTypesStillFail() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Future<int> pending): int {
                  return 1;
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc bad(Mutex<int> lock): int {
                  return 1;
                }
                """)));
    }

    @Test
    void channelOwnershipCannotBeTransferredTwice() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                async fnc consume(Channel<int> input): int {
                  return await (nb readch input);
                }
                fnc bad(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  val Future<int> one = consume(rt take channel);
                  val Future<int> two = consume(rt take channel);
                  return;
                }
                """)));
    }
}

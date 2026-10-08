package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Local channels carry owned callables; actor wire envelopes never do. */
final class ChannelCallableTransportTest {
    @Test
    void localChannelsAdmitOwnedFunctionsAndNestedCallablePayloads() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc twice(int value): int { return value * 2; }
                fnc ok(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  writech handlers, callback;
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                fnc ok(Channel<HandlerBox> handlers): void { return; }
                """)));
    }

    @Test
    void transferredCallableCannotBeUsedAgainBySender() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  writech handlers, callback;
                  callback(2);
                  return;
                }
                """)));
    }

    @Test
    void actorCallableParametersRejectFunctionsTransitively() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                actor fnc bad(Fnc<int, int> callback): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define class HandlerBox as
                  val Fnc<int, int> callback;
                end
                actor fnc bad(HandlerBox box): void { return; }
                """)));
    }

    @Test
    void borrowedPayloadTypesCannotOutliveChannelStorage() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<&int> borrowed): void { return; }
                """)));
    }

    @Test
    void nestedBorrowedPayloadsAreRejectedBeforeChannelCreation() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Option<&int>> payload): void { return; }
                """)));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Channel<Array<&int>> payload): void { return; }
                """)));
    }

    @Test
    void nbSelectRegistrationOwnsEveryMoveOnlyWriteCandidate() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  nb select {
                    case writech handlers, callback: { }
                  }
                  callback(2);
                  return;
                }
                """)));
    }

    @Test
    void nbSelectCannotRegisterSameMoveOnlyPayloadTwice() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): void {
                  val Channel<Fnc<int, int>> a = Channel.new<Fnc<int, int>>(1);
                  val Channel<Fnc<int, int>> b = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> callback = |x| -> { return x * 2; };
                  nb select {
                    case writech a, callback: { }
                    case writech b, callback: { }
                  }
                  return;
                }
                """)));
    }

    @Test
    void ordinaryDataChannelsRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc apply(Fnc<int, int> callback, int value): int {
                  return callback(value);
                }
                fnc ok(): void {
                  val Fnc<int, int> twice = |value| -> { return value * 2; };
                  val int result = apply(twice, 21);
                  val Channel<int> values = Channel.new<int>(1);
                  writech values, result;
                  return;
                }
                """)));
    }

    @Test
    void localChannelCarriesCallableAtRuntime() throws Exception {
        String program = """
                pub routine main(): void {
                  val Channel<Fnc<int, int>> handlers = Channel.new<Fnc<int, int>>(1);
                  val Fnc<int, int> twice = |value| -> { return value * 2; };
                  writech handlers, twice;
                  val Fnc<int, int> received = readch handlers;
                  stdio.stdout.write(received(21));
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "callable-channel.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

}

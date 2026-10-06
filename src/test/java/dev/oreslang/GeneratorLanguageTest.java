package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GeneratorLanguageTest {
    @Test
    void suspendableSyncIterationClosesProducerBeforeContinuingAfterBreak() throws Exception {
        assertEquals("1:closed|done", run("""
                generator fnc values(): int {
                  try { yield 1; yield 2; } catch (err) { }
                  finally { stdio.stdout.write(":closed"); }
                  return;
                }
                pub async routine main(): void {
                  for const item of values() {
                    stdio.stdout.write(item);
                    break;
                  }
                  stdio.stdout.write("|done");
                  return;
                }
                """));
    }

    @Test
    void suspendableAsyncIterationClosesProducerOnBreak() throws Exception {
        assertEquals("1:closed", run("""
                async generator fnc values(): int {
                  try {
                    yield 1;
                    yield 2;
                  } catch (err) {
                  } finally {
                    stdio.stdout.write(":closed");
                  }
                  return;
                }
                pub async routine main(): void {
                  for await const item of values() {
                    stdio.stdout.write(item);
                    break;
                  }
                  return;
                }
                """));
    }

    @Test
    void iteratorResultDataCrossesOrdinaryAsyncBoundary() throws Exception {
        assertEquals("9:true", run("""
                generator fnc values(): int { yield 9; return; }
                async fnc pull(): IteratorResult<int> {
                  val Iterator<int> iterator = values();
                  val step = iterator.next();
                  iterator.close();
                  return step;
                }
                pub routine main(): void {
                  val IteratorResult<int> step = await pull();
                  stdio.stdout.write(step.value.unwrap());
                  stdio.stdout.write(":");
                  stdio.stdout.write(step.value.is_some());
                  return;
                }
                """));
        IllegalArgumentException erased = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("fnc check(IteratorResult<int> result): bool { return result is type IteratorResult<int>; }")));
        assertTrue(erased.getMessage().contains("cannot test parameterized type"), erased.getMessage());
    }
    @Test
    void publicIteratorProtocolsExposeTypedPullResultsAndKeepLegacyAliases() throws Exception {
        assertEquals("false:7:true:true:8:true:77", run("""
                generator fnc values(): int { yield 7; return; }
                async generator fnc stream(): int { yield 8; return; }
                pub routine main(): void {
                  val Iterator<int> iterator = values();
                  val IteratorResult<int> first = iterator.next();
                  stdio.stdout.write(first.done);
                  stdio.stdout.write(":");
                  stdio.stdout.write(first.value.unwrap());
                  stdio.stdout.write(":");
                  val IteratorResult<int> terminal = iterator.next();
                  stdio.stdout.write(terminal.done);
                  stdio.stdout.write(":");
                  stdio.stdout.write(terminal.value.is_none());
                  stdio.stdout.write(":");
                  val AsyncIterator<int> asynchronous = stream();
                  val IteratorResult<int> pulled = await asynchronous.next();
                  stdio.stdout.write(pulled.value.unwrap());
                  stdio.stdout.write(":");
                  asynchronous.close();
                  val IteratorResult<int> closed = await asynchronous.next();
                  stdio.stdout.write(closed.done);
                  stdio.stdout.write(":");
                  val Generator<int> legacy = values();
                  for const value of legacy { stdio.stdout.write(value); }
                  val Iterator<int> current = values();
                  for const value of current { stdio.stdout.write(value); }
                  return;
                }
                """));
    }

    @Test
    void iteratorResultsAreReadonlyAndPullArityIsChecked() {
        for (String statement : new String[]{"iterator.next(1);", "result.done = true;", "result.value = None;"}) {
            assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                    generator fnc values(): int { yield 1; return; }
                    fnc bad(): void {
                      val Iterator<int> iterator = values();
                      val IteratorResult<int> result = iterator.next();
                    """ + statement + "\nreturn;\n}")));
        }
    }
    @Test
    void generatorFncAndRoutineArePullDrivenIterables() throws Exception {
        String output = run("""
                generator fnc first(): int {
                  yield 1;
                  yield 2;
                  return;
                }

                generator routine second(): int {
                  yield 3;
                  yield 4;
                  return;
                }

                pub routine main(): void {
                  for const value of first() do
                    stdio.stdout.write(value);
                  done
                  for const value of second() do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """);

        assertEquals("1234", output);
    }

    @Test
    void asyncGeneratorComposesAwaitAndForAwait() throws Exception {
        String output = run("""
                async fnc later(int value): int {
                  return value;
                }

                async generator fnc values(): int {
                  yield 1;
                  yield await later(2);
                  yield 3;
                  return;
                }

                pub async fnc main(): void {
                  for await const value of values() do
                    stdio.stdout.write(value);
                  done
                  return;
                }
                """);

        assertEquals("123", output);
    }

    @Test
    void generatorAndAsyncModifiersAreOrderIndependent() {
        AstAssertions.assertAsyncGenerator(Parser.parse("""
                generator async routine values(): int {
                  yield 1;
                  return;
                }
                """));
        AstAssertions.assertAsyncGenerator(Parser.parse("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }
                """));
    }

    @Test
    void forAwaitAdmitsSynchronousArraysGeneratorsAndIteratorProtocols() throws Exception {
        assertEquals("123456", run("""
                generator fnc values(): int {
                  yield 3;
                  yield 4;
                  return;
                }
                define class Bag as
                  pub [Symbol.iterator](): Array<int> { return [5, 6]; }
                end
                pub async routine main(): void {
                  for await const value of [1, 2] { stdio.stdout.write(value); }
                  for await const value of values() { stdio.stdout.write(value); }
                  for await const value of new Bag() { stdio.stdout.write(value); }
                  return;
                }
                """));
    }

    @Test
    void classCanExposeAsyncIteratorProtocolWithoutGeneratorMethods() throws Exception {
        String output = run("""
                async generator fnc stream(): int {
                  yield 7;
                  yield 8;
                  return;
                }

                define class Bag as
                  pub [Symbol.asyncIterator](): AsyncGenerator<int> {
                    return stream();
                  }
                end

                pub async routine main(): void {
                  val bag = new Bag();
                  for await const item of bag {
                    stdio.stdout.write(item);
                  }
                  return;
                }
                """);

        assertEquals("78", output);
    }

    @Test
    void generatorStarSugarAndCooperateComposeAcrossSyncAndAsyncIteration() throws Exception {
        assertEquals("1234", run("""
                async fnc later(int value): int {
                  return value;
                }

                fnc gen* sync_values(): Iterator<int> {
                  rt cooperate;
                  yield 1;
                  rt yield;
                  yield 2;
                  return;
                }

                async fnc generator* async_values(): AsyncIterator<int> {
                  rt cooperate;
                  yield await later(3);
                  rt yield;
                  yield await later(4);
                  return;
                }

                pub async fnc main(): void {
                  val Iterator<int> sync = sync_values();
                  for const value of sync {
                    stdio.stdout.write(value);
                  }

                  val AsyncIterator<int> asynchronous = async_values();
                  for await const value of asynchronous {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));
    }

    @Test
    void yieldStarDelegatesSyncAsyncAndSynchronousSources() throws Exception {
        assertEquals("12345|67812345", run("""
                fnc gen* inner(): Iterator<int> {
                  yield 2;
                  rt cooperate;
                  yield 3;
                  return;
                }

                fnc gen* outer(): Iterator<int> {
                  yield 1;
                  yield* inner();
                  yield* [4, 5];
                  return;
                }

                async fnc gen* async_inner(): AsyncIterator<int> {
                  yield 7;
                  rt cooperate;
                  yield 8;
                  return;
                }

                async fnc gen* async_outer(): AsyncIterator<int> {
                  yield 6;
                  yield* async_inner();
                  yield* outer();
                  return;
                }

                pub async fnc main(): void {
                  for const value of outer() {
                    stdio.stdout.write(value);
                  }
                  stdio.stdout.write("|");
                  for await const value of async_outer() {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));
    }

    @Test
    void yieldStarClosesDelegatedGeneratorWhenOuterIteratorCloses() throws Exception {
        assertEquals("1:inner-closed", run("""
                fnc gen* inner(): Iterator<int> {
                  try {
                    yield 1;
                    yield 2;
                  } catch (err) {
                  } finally {
                    stdio.stdout.write(":inner-closed");
                  }
                  return;
                }

                fnc gen* outer(): Iterator<int> {
                  yield* inner();
                  return;
                }

                pub fnc main(): void {
                  val Iterator<int> iterator = outer();
                  val IteratorResult<int> first = iterator.next();
                  stdio.stdout.write(first.value.unwrap());
                  iterator.close();
                  return;
                }
                """));
    }

    @Test
    void yieldStarRejectsAsyncDelegationFromSyncGeneratorAndElementMismatch() {
        IllegalArgumentException asyncIntoSync = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc gen* async_values(): AsyncIterator<int> {
                          yield 1;
                          return;
                        }
                        fnc gen* bad(): Iterator<int> {
                          yield* async_values();
                          return;
                        }
                        """));
        assertTrue(asyncIntoSync.getMessage().contains("for await")
                        || asyncIntoSync.getMessage().contains("Async"),
                asyncIntoSync::getMessage);

        IllegalArgumentException wrongElement = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc gen* bad(): Iterator<int> {
                          yield* ["wrong"];
                          return;
                        }
                        """));
        assertTrue(wrongElement.getMessage().contains("yield* element"), wrongElement::getMessage);
    }

    @Test
    void explicitGeneratorProtocolAnnotationsRejectSyncAsyncMismatches() {
        IllegalArgumentException sync = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc gen* bad(): AsyncIterator<int> {
                          yield 1;
                          return;
                        }
                        """));
        assertTrue(sync.getMessage().contains("Iterator<T>"), sync::getMessage);

        IllegalArgumentException asynchronous = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        async fnc gen* bad(): Iterator<int> {
                          yield 1;
                          return;
                        }
                        """));
        assertTrue(asynchronous.getMessage().contains("AsyncIterator<T>"), asynchronous::getMessage);
    }

    @Test
    void generatorStarSugarDoesNotStealOrdinaryFunctionNamedGen() throws Exception {
        assertEquals("9", run("""
                fnc gen(): int { return 9; }
                pub fnc main(): void {
                  stdio.stdout.write(gen());
                  return;
                }
                """));
    }

    @Test
    void yieldAndGeneratorReturnsAreCheckedStatically() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc ordinary(): int {
                  yield 1;
                  return 1;
                }
                """));

        IllegalArgumentException returned = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        generator fnc bad(): int {
                          return 1;
                        }
                        """));
        assertTrue(returned.getMessage().contains("generator return"));
    }

    @Test
    void asyncAndSyncIterationCannotBeAccidentallyMixed() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }

                pub async fnc main(): void {
                  for const value of values() {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                async generator fnc values(): int {
                  yield 1;
                  return;
                }

                pub routine main(): void {
                  for await const value of values() {
                    stdio.stdout.write(value);
                  }
                  return;
                }
                """));
    }

    @Test
    void generatorMethodsAndActorGeneratorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                define class Bad as
                  pub generator next(): int {
                    yield 1;
                    return;
                  }
                end
                """));

        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                generator actor fnc bad(): int {
                  yield 1;
                  return;
                }
                """));
    }

    @Test
    void yieldCannotSuspendWithLiveBorrow() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub val int value = 1;
                end

                generator fnc bad(&Box box): int {
                  yield box.value;
                  return;
                }
                """));
    }

    @Test
    void synchronousForAwaitAdapterStillRejectsLiveBorrows() {
        var error = assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                define class Box as
                  pub val int value = 1;
                end
                async fnc bad(): void {
                  val Box box = new Box();
                  val &Box borrowed = &box;
                  for await const value of [1, 2] { stdio.stdout.write(value); }
                  stdio.stdout.write(borrowed.value);
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("cannot suspend for async iteration"), error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "generators.ores")
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

    private static final class AstAssertions {
        private static void assertAsyncGenerator(dev.oreslang.ast.Ast.Program program) {
            var fn = (dev.oreslang.ast.Ast.FunctionDecl)
                    program.modules().getFirst().declarations().getFirst();
            assertTrue(fn.async());
            assertTrue(fn.generator());
        }
    }
}

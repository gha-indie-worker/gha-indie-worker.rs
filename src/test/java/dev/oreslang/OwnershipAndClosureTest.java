package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class OwnershipAndClosureTest {

    @Test
    void lexicalClosureEscapesAndRetainsMutableCapturedState() throws Exception {
        String output = run("""
                fnc makeCounter(): (() => int) {
                  let int count = 0;
                  return || -> {
                    count = count + 1;
                    return count;
                  };
                }

                pub routine main(): void {
                  val (() =>int) counter = makeCounter();
                  stdio.stdout.write(counter());
                  stdio.stdout.write(counter());
                  return;
                }
                """);
        assertEquals("12", output);
    }

    @Test
    void ordinaryParametersAreImmutableForFieldMutation() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc change(Bar b): void {
                          b.foo = "foobar";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("read-only binding"));
    }

    @Test
    void ownedMutParameterMayMutateAndReturnOwnership() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(Bar mut b): Bar {
                  b.foo = "foobar";
                  return b;
                }

                pub routine main(): void {
                  let Bar b = new Bar();
                  let Bar changed = change(b);
                  stdio.stdout.write(changed.foo);
                  return;
                }
                """);
        assertEquals("foobar", output);
    }

    @Test
    void explicitMutableMethodReceiverMayMutateSelf() throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;

                  pub bump(self &mut Counter)(): void {
                    self.value = self.value + 1;
                    return;
                  }
                end

                pub routine main(): void {
                  let mut Counter counter = new Counter();
                  counter.bump();
                  counter.bump();
                  stdio.stdout.write(counter.value);
                  return;
                }
                """);
        assertEquals("2", output);
    }

    @Test
    void explicitMutableMethodReceiverRejectsImmutableOwner() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Counter as
                          pub let int value = 0;

                          pub bump(self &mut Counter)(): void {
                            self.value = self.value + 1;
                            return;
                          }
                        end

                        fnc bad(): void {
                          const Counter counter = new Counter();
                          counter.bump();
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("mutable method receiver"));
    }

    @Test
    void mutableBorrowAllowsMutationWithoutMovingOwner() throws Exception {
        String output = run("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc change(&mut Bar b): void {
                  b.foo = "borrowed";
                  return;
                }

                pub routine main(): void {
                  let mut Bar b = new Bar();
                  change(&mut b);
                  stdio.stdout.write(b.foo);
                  return;
                }
                """);
        assertEquals("borrowed", output);
    }

    @Test
    void immutableBorrowBlocksOverlappingMutableBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc mutate(&mut Bar b): void {
                          b.foo = "changed";
                          return;
                        }

                        fnc bad(): void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          mutate(&mut b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void useAfterMoveIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b): void {
                          return;
                        }

                        fnc bad(): void {
                          let Bar b = new Bar();
                          consume(b);
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void borrowOfLocalCannotEscapeFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad(): &Bar {
                          let Bar b = new Bar();
                          return &b;
                        }
                        """)));
        assertTrue(error.getMessage().contains("outlive its owner"));
    }

    @Test
    void borrowedParameterCanBeReturned() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc identity(&Bar b): &Bar {
                  return b;
                }
                """)));
    }


    @Test
    void multipleImmutableBorrowsMayCoexist() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc ok(): void {
                  let Bar b = new Bar();
                  val &Bar first = &b;
                  val &Bar second = &b;
                  stdio.println(first.foo);
                  stdio.println(second.foo);
                  return;
                }
                """)));
    }

    @Test
    void secondMutableBorrowIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad(): void {
                          let Bar b = new Bar();
                          val &mut Bar first = &mut b;
                          val &mut Bar second = &mut b;
                          stdio.println(first.foo);
                          stdio.println(second.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"));
    }

    @Test
    void moveWhileBorrowedIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b): void { return; }

                        fnc bad(): void {
                          let Bar b = new Bar();
                          val &Bar read = &b;
                          consume(b);
                          stdio.println(read.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot move"));
    }

    @Test
    void lexicalScopeEndsStoredBorrow() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc mutate(&mut Bar b): void {
                  b.foo = "changed";
                  return;
                }

                fnc ok(): void {
                  let mut Bar b = new Bar();
                  if true; do
                    val &Bar read = &b;
                    stdio.println(read.foo);
                  fi
                  mutate(&mut b);
                  return;
                }
                """)));
    }


    @Test
    void moveInBothIfBranchesIsAllowedButValueIsMovedAfterJoin() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Bar as
                  pub let String foo = "start";
                end

                fnc consume(Bar b): void { return; }

                fnc ok(bool flag): void {
                  let Bar b = new Bar();
                  if flag; do
                    consume(b);
                  else
                    consume(b);
                  fi
                  return;
                }
                """)));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc consume(Bar b): void { return; }

                        fnc bad(bool flag): void {
                          let Bar b = new Bar();
                          if flag; do
                            consume(b);
                          else
                            consume(b);
                          fi
                          stdio.println(b.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'b'"));
    }

    @Test
    void immutableFieldStaysImmutableEvenThroughMutOwner() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub val String foo = "start";
                        end

                        fnc bad(Bar mut b): void {
                          b.foo = "changed";
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("field 'Bar.foo' is immutable"));
    }

    @Test
    void moveOnlyCaptureTransfersIntoClosure() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val (() => int) read = || -> {
                            return box.value;
                          };
                          stdio.println(box.value);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    @Test
    void nestedExpressionLambdaTransitivelyMovesOuterMoveOnlyCapture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val (() => (() => int)) outer = || -> || -> box.value;
                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    @Test
    void nestedBlockLambdaTransitivelyMovesOuterMoveOnlyCapture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Box as
                          pub val int value = 7;
                        end

                        fnc bad(): void {
                          let Box box = new Box();
                          val (() => (() => int)) outer = || -> {
                            return || -> {
                              return box.value;
                            };
                          };
                          stdio.println(box.value);
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("use of moved value 'box'"));
    }

    @Test
    void explicitNlexNestedLambdaDoesNotCreateTransitiveCapture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val int outer_value = 7;
                          val (() => (() => int)) outer = || -> nlex || -> outer_value;
                          return;
                        }
                        """)));

        assertTrue(error.getMessage().contains("outer_value")
                || error.getMessage().contains("unknown name"));
    }

    @Test
    void storedBorrowOfLocalCannotEscapeFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad(): &Bar {
                          let Bar b = new Bar();
                          val &Bar view = rt borrow b;
                          return view;
                        }
                        """)));
        assertTrue(error.getMessage().contains("returned borrows")
                || error.getMessage().contains("outlive"), error::getMessage);
    }

    @Test
    void borrowOfOwnedParameterCannotEscapeFunction() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad(Bar b): &Bar {
                          return &b;
                        }
                        """)));
        assertTrue(error.getMessage().contains("returned borrows")
                || error.getMessage().contains("outlive"), error::getMessage);
    }

    @Test
    void borrowCannotBeStoredInOwnedArray() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc bad(): void {
                          let Bar b = new Bar();
                          val values = [rt borrow b];
                          stdio.println(values);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("borrowed references cannot be stored"), error::getMessage);
    }

    @Test
    void classFieldCannotHaveBorrowedType() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        define class Holder as
                          pub val &Bar value;
                        end
                        """)));
        assertTrue(error.getMessage().contains("cannot store a borrowed reference"), error::getMessage);
    }

    @Test
    void awaitRejectsLiveOrdinaryBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        async fnc answer(): int {
                          return 42;
                        }

                        async fnc bad(): int {
                          let Bar b = new Bar();
                          val &Bar view = rt borrow b;
                          val result = await answer();
                          stdio.println(view.foo);
                          return result;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot await while an ordinary borrow is live"), error::getMessage);
    }

    @Test
    void immutableBorrowAliasesDoNotCorruptOwnerBorrowCount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        define class Bar as
                          pub let String foo = "start";
                        end

                        fnc mutate(&mut Bar b): void {
                          b.foo = "changed";
                          return;
                        }

                        fnc bad(): void {
                          let Bar b = new Bar();
                          if true; do
                            val &Bar first = rt borrow b;
                            val &Bar second = first;
                            stdio.println(second.foo);
                          fi
                          val &Bar third = rt borrow b;
                          mutate(&mut b);
                          stdio.println(third.foo);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().toLowerCase().contains("borrow"), error::getMessage);
    }

    @Test
    void actorSelfBoundMethodCannotEscapeMailboxTurn() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        actor Worker {
                          private helper() : int {
                            return 7;
                          }

                          private leak() : Fnc<int> {
                            return self.helper;
                          }
                        }
                        """)));

        assertTrue(error.getMessage().contains("cannot escape its mailbox turn as a bound method"));
    }


    @Test
    void ordinaryClassFieldProjectionIsAReadNotAnImplicitPartialMove() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Child as
                  pub let String value = "a";
                end

                define class Holder as
                  pub val Child child = new Child();
                end

                fnc ok(): void {
                  val Holder holder = new Holder();
                  val Child alias = holder.child;
                  alias.value = "alias";
                  holder.child.value = "owner";
                  return;
                }
                """)));
    }

    @Test
    void ordinaryRecordFieldProjectionIsAReadNotAnImplicitPartialMove() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc seed(): string { return "a"; }

                fnc ok(): void {
                  val outer = struct{inner: {value: string}}{inner: struct{value: string}{value: seed()}};
                  val alias = outer.inner;
                  alias.value = "alias";
                  outer.inner.value = "owner";
                  return;
                }
                """)));
    }

    @Test
    void ordinaryIndexedProjectionIsAReadNotAnImplicitPartialMove() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc seed(): string { return "a"; }

                fnc ok(): void {
                  val items = arr[struct{value: string}{value: seed()}];
                  val alias = items[0];
                  alias.value = "alias";
                  items[0].value = "owner";
                  return;
                }
                """)));
    }

    @Test
    void copyFieldAndIndexedElementExtractionRemainLegal() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Numbers as
                  pub val int answer = 42;
                end

                fnc ok(): int {
                  val Numbers numbers = new Numbers();
                  val int from_field = numbers.answer;
                  val values = arr[1, 2, 3];
                  val int from_index = values[1];
                  return from_field + from_index;
                }
                """)));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "ownership.ores")
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
}

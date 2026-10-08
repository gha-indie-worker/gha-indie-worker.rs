package dev.oreslang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SelectAtomicDispatchContractTest {
    @Test
    void prioritySelectExecutesExactlyOneReadyArm() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> one = Channel.new<int>(1);
                  val Channel<int> two = Channel.new<int>(1);
                  writech one, 1;
                  writech two, 2;
                  do select first {
                    case readch one: val value {
                      stdio.stdout.write("one:");
                      stdio.stdout.write(value);
                    }
                    case readch two: val value {
                      stdio.stdout.write("two:");
                      stdio.stdout.write(value);
                    }
                    default: {
                      stdio.stdout.write("default:");
                    }
                  }
                  stdio.stdout.write("done");
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("one:1done", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void prioritySelectWriteCommitsExactlyOnePayload() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> outgoing = Channel.new<int>(1);
                  do select first {
                    case writech outgoing, 11: {
                      stdio.stdout.write("sent:");
                    }
                    default: {
                      stdio.stdout.write("default:");
                    }
                  }
                  val int result = readch outgoing;
                  stdio.stdout.write(result);
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("sent:11", SelectNestedReturnContractTest.run(source));
    }

    @Test
    void blockingDoSelectIsStillStatementNotValueExpression() throws Exception {
        String source = """
                pub fnc main(): void {
                  do select {
                    default: {
                      stdio.stdout.write("effect");
                    }
                  }
                  stdio.stdout.write(":next");
                  return;
                }
                """;
        SelectNestedReturnContractTest.assertWarnings(source, 0);
        assertEquals("effect:next", SelectNestedReturnContractTest.run(source));
    }
}

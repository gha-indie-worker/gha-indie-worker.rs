package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

final class ChannelSelectSyntaxTest {
    @Test
    void parsesBlockingAndNonBlockingStaticSelect() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc blocking(
                    Channel<string> incoming,
                    Channel<string> payload,
                    Channel<bool> finished
                ): void {
                  select {
                    case readch incoming: let msg {
                      stdio.println(msg);
                    }
                    case readch payload: const body {
                      stdio.println(body);
                    }
                    case readch finished: {
                      return;
                    }
                  }
                  return;
                }

                actor fnc nonblocking(): void {
                  val Channel<string> incoming = Channel.new<string>(1);
                  val Channel<string> payload = Channel.new<string>(1);
                  val Channel<bool> finished = Channel.new<bool>(1);

                  nb select {
                    case readch incoming: let msg {
                      stdio.println(msg);
                    }
                    case readch payload: const body {
                      stdio.println(body);
                    }
                    case readch finished: const signal {
                      return;
                    }
                  }
                  stdio.println("continued immediately");
                  return;
                }
                """));

        Ast.FunctionDecl blocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.SelectStmt blockingSelect =
                assertInstanceOf(Ast.SelectStmt.class, blocking.body().getFirst());
        assertEquals(Ast.WaitMode.BLOCKING, blockingSelect.mode());
        assertEquals(Ast.SelectPolicy.FAIR, blockingSelect.policy());
        assertEquals(Ast.BindingKind.LET, blockingSelect.arms().getFirst().bindingKind());
        assertEquals(Ast.BindingKind.CONST, blockingSelect.arms().get(1).bindingKind());

        Ast.FunctionDecl nonblocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.SelectStmt nb =
                assertInstanceOf(
                        Ast.SelectStmt.class,
                        nonblocking.body().stream()
                                .filter(statement -> statement instanceof Ast.SelectStmt)
                                .findFirst()
                                .orElseThrow());
        assertEquals(Ast.WaitMode.NONBLOCKING, nb.mode());

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void staticSelectAcceptsWhenAndLegacyCaseAsEquivalentArmKeywords() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc blocking(Channel<int> input, Channel<int> output): void {
                  do select first {
                    when readch input: val value {
                      stdio.println(value);
                    }
                    case writech output, 7: {
                      stdio.println("sent");
                    }
                    default: {
                    }
                  }
                  return;
                }

                actor fnc nonblocking(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  do nb select {
                    when readch input: const value {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """));

        Ast.FunctionDecl blocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.SelectStmt select = assertInstanceOf(Ast.SelectStmt.class, blocking.body().getFirst());
        assertEquals(3, select.arms().size());
        assertEquals(Ast.ChannelOperation.READ, select.arms().get(0).operation());
        assertEquals(Ast.ChannelOperation.WRITE, select.arms().get(1).operation());
        assertEquals(Ast.ChannelOperation.DEFAULT, select.arms().get(2).operation());

        Ast.FunctionDecl nonblocking =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.SelectStmt nb = assertInstanceOf(Ast.SelectStmt.class, nonblocking.body().get(1));
        assertEquals(Ast.WaitMode.NONBLOCKING, nb.mode());

        assertDoesNotThrow(() -> OwnershipChecker.check(program));
    }

    @Test
    void explicitDoSelectParsesAsNoResultDispatchWithoutChangingLegacySelect() {
        Ast.Program checked = TypeChecker.check(Parser.parse("""
                fnc consume(Channel<int> input): void {
                  do select first {
                    case readch input: val value {
                      stdio.println(value);
                    }
                  }
                  select {
                    case readch input: val another {
                      stdio.println(another);
                    }
                  }
                  return;
                }

                actor fnc consumeLater(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  do nb select random {
                    case readch input: const value {
                      stdio.println(value);
                    }
                  }
                  nb select {
                    case readch input: const another {
                      stdio.println(another);
                    }
                  }
                  return;
                }
                """));

        Ast.FunctionDecl consume =
                (Ast.FunctionDecl) checked.modules().getFirst().declarations().get(0);
        Ast.SelectStmt synchronous = (Ast.SelectStmt) consume.body().get(0);
        assertTrue(synchronous.explicitDo());
        assertEquals(Ast.WaitMode.BLOCKING, synchronous.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, synchronous.policy());
        assertEquals(false, ((Ast.SelectStmt) consume.body().get(1)).explicitDo());

        Ast.FunctionDecl later =
                (Ast.FunctionDecl) checked.modules().getFirst().declarations().get(1);
        Ast.SelectStmt asynchronous = (Ast.SelectStmt) later.body().get(1);
        assertTrue(asynchronous.explicitDo());
        assertEquals(Ast.WaitMode.NONBLOCKING, asynchronous.mode());
        assertEquals(Ast.SelectPolicy.RANDOM, asynchronous.policy());
        assertEquals(false, ((Ast.SelectStmt) later.body().get(2)).explicitDo());

        assertDoesNotThrow(() -> OwnershipChecker.check(checked));
    }

    @Test
    void callbackSelectAliasesAndUnsafeDynamicNoResultFormsAreRejected() {
        String[] invalid = {
            "cb select { default: {} }",
            "nb cb select { default: {} }",
            "do try select { default: {} }",
            "do select from cases;",
            "do nb select from cases;"
        };
        for (String selected : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc invalid(): void { " + selected + " return; }"),
                    selected);
        }
    }

    @Test
    void explicitDoSelectCannotEscapeItsActorBeforeDeferredArmRuns() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc invalid(Channel<int> input): void {
                  do nb select {
                    case readch input: val value {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void staticNbSelectRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(Channel<int> input): void {
                  nb select {
                    case readch input: val value {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc right(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  nb select {
                    case readch input: val value {
                      stdio.println(value);
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void selectPolicyIsFairByDefaultAndPriorityOrRandomOnlyWhenExplicit() {
        Ast.Program program = Parser.parse("""
                fnc policies(Channel<int> a, Channel<int> b): void {
                  select {
                    case readch a: val x {
                      stdio.println(x);
                    }
                    case readch b: val y {
                      stdio.println(y);
                    }
                  }

                  select first {
                    case readch a: val x {
                      stdio.println(x);
                    }
                    case readch b: val y {
                      stdio.println(y);
                    }
                  }

                  select random {
                    case readch a: val x {
                      stdio.println(x);
                    }
                    case readch b: val y {
                      stdio.println(y);
                    }
                  }
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        assertEquals(
                Ast.SelectPolicy.FAIR,
                ((Ast.SelectStmt) fn.body().get(0)).policy());
        assertEquals(
                Ast.SelectPolicy.PRIORITY,
                ((Ast.SelectStmt) fn.body().get(1)).policy());
        assertEquals(
                Ast.SelectPolicy.RANDOM,
                ((Ast.SelectStmt) fn.body().get(2)).policy());
    }

    @Test
    void parsesImmediateChannelProbesWithoutConfusingTryCatch() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc probe(Channel<int> input, Channel<int> output): void {
                  val Option<int> read = try readch input;
                  val bool wrote = try writech output, 42;

                  try {
                    stdio.println("ordinary try still works");
                  } catch (err) {
                    stdio.println(err);
                  }
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt read = (Ast.BindingStmt) fn.body().get(0);
        Ast.ChannelOpExpr readOp =
                assertInstanceOf(Ast.ChannelOpExpr.class, read.initializer());
        assertEquals(Ast.WaitMode.IMMEDIATE, readOp.mode());
        assertEquals(Ast.ChannelOperation.READ, readOp.operation());

        Ast.BindingStmt wrote = (Ast.BindingStmt) fn.body().get(1);
        Ast.ChannelOpExpr writeOp =
                assertInstanceOf(Ast.ChannelOpExpr.class, wrote.initializer());
        assertEquals(Ast.WaitMode.IMMEDIATE, writeOp.mode());
        assertEquals(Ast.ChannelOperation.WRITE, writeOp.operation());

        assertInstanceOf(Ast.TryStmt.class, fn.body().get(2));
    }

    @Test
    void parsesDynamicSelectFromRuntimeCollections() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc choose(Array<SelectCase> cases): Option<SelectResult> {
                  return select from cases;
                }

                fnc arm(Array<SelectCase> cases): Future<Option<SelectResult>> {
                  return nb select first from cases;
                }

                fnc probe(Array<SelectCase> cases): Option<SelectResult> {
                  return try select from cases;
                }
                """));

        Ast.FunctionDecl choose =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(0);
        Ast.DynamicSelectExpr blocking =
                assertInstanceOf(
                        Ast.DynamicSelectExpr.class,
                        ((Ast.ReturnStmt) choose.body().getFirst()).value());
        assertEquals(Ast.WaitMode.BLOCKING, blocking.mode());
        assertEquals(Ast.SelectPolicy.FAIR, blocking.policy());

        Ast.FunctionDecl arm =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().get(1);
        Ast.DynamicSelectExpr nb =
                assertInstanceOf(
                        Ast.DynamicSelectExpr.class,
                        ((Ast.ReturnStmt) arm.body().getFirst()).value());
        assertEquals(Ast.WaitMode.NONBLOCKING, nb.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, nb.policy());
    }

    @Test
    void staticSelectSupportsWriteAndDefaultArms() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc send(Channel<string> output, string payload): void {
                  try select first {
                    case writech output, payload: {
                      stdio.println("sent");
                    }
                    default: {
                      stdio.println("busy");
                    }
                  }
                  return;
                }
                """)));
    }

    @Test
    void channelAndDynamicCaseFactoriesTypeCheckEndToEnd() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc make(): Channel<int> {
                  return Channel.new<int>(16);
                }

                fnc arm(): Future<Option<SelectResult>> {
                  val Channel<int> input = Channel.new<int>(4);
                  val Channel<int> output = Channel.new<int>(4);
                  val Array<SelectCase> cases = [
                    SelectCase.read(input),
                    SelectCase.write(output, 42)
                  ];
                  return nb select from cases;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(): Channel<void> {
                  return Channel.new<void>(1);
                }
                """)));
    }

    @Test
    void nonblockingWriteHasRepresentableFutureVoidSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc write(Channel<int> output): Future<void> {
                  return nb writech output, 42;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class, fn.body().getFirst());
        Ast.ChannelOpExpr write =
                assertInstanceOf(Ast.ChannelOpExpr.class, returned.value());
        assertEquals(Ast.WaitMode.NONBLOCKING, write.mode());
        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertEquals(false, write.callback());
    }

    @Test
    void nonblockingWriteCallbackUsesTheSameChannelOperationSurface() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor fnc write(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  nb cb writech output, 42 || -> {
                    stdio.println("write complete");
                  };
                  return;
                }
                """));

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.ExprStmt statement =
                assertInstanceOf(Ast.ExprStmt.class, fn.body().get(1));
        Ast.ChannelOpExpr write =
                assertInstanceOf(Ast.ChannelOpExpr.class, statement.expression());
        assertEquals(Ast.WaitMode.NONBLOCKING, write.mode());
        assertEquals(Ast.ChannelOperation.WRITE, write.operation());
        assertTrue(write.callback());
        assertEquals(1, write.callbackBody().size());
    }

    @Test
    void nonblockingWriteCallbackRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc wrong(Channel<int> output): void {
                  nb cb writech output, 42 || -> {
                    stdio.println("wrong");
                  };
                  return;
                }
                """)));
    }

    @Test
    void channelAndSelectCapabilitiesCannotCrossActorBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc bad(Channel<int> channel): int {
                  return 1;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                pub actor fnc bad_result(Array<SelectCase> cases): SelectResult {
                  return select from cases;
                }
                """)));
    }

    @Test
    void nbSelectMovesMoveOnlyCapturesIntoDeferredContinuation() {
        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(): void {
                          val Channel<int> input = Channel.new<int>(1);
                          val Array<int> owned = [1, 2, 3];

                          nb select {
                            case readch input: val value {
                              stdio.println(owned[0]);
                            }
                          }

                          stdio.println(owned[0]);
                          return;
                        }
                        """)));

        assertTrue(
                moved.getMessage().contains("moved value")
                        || moved.getMessage().contains("cannot use moved"),
                moved::getMessage);
    }

    @Test
    void nbSelectMayCaptureCopyValues() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                actor fnc copy_capture(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val int label = 7;

                  nb select {
                    case readch input: val value {
                      stdio.println(label + value);
                    }
                  }

                  stdio.println(label);
                  return;
                }

                """)));
    }

    @Test
    void captureScannerTraversesNestedChannelAndSelectSyntax() {
        IllegalArgumentException moved = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(): void {
                          val Channel<int> input = Channel.new<int>(1);
                          val Array<int> owned = [1, 2, 3];

                          val (() => void) callback = || -> {
                            nb select {
                              case readch input: val value {
                                stdio.println(owned[0]);
                              }
                            }
                            return;
                          };

                          callback();
                          stdio.println(owned[0]);
                          return;
                        }
                        """)));

        assertTrue(
                moved.getMessage().contains("moved value")
                        || moved.getMessage().contains("cannot use moved"),
                moved::getMessage);
    }

    @Test
    void rejectsMalformedChannelAndSelectForms() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  nb select;
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  select {
                  case readch ch
                    return;
                  }
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Channel<int> ch): void {
                  select {
                    default: {
                      return;
                    }
                    default: {
                      return;
                    }
                  }
                  return;
                }
                """));
    }
}

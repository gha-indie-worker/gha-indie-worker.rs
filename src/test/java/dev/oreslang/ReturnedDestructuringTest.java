package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ReturnedDestructuringTest {
    @Test
    void unionArraysAndBindingKindPropagationTypeCheck() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type intOrBoolOrString = bool | int | string;

                pub fnc mixed(): Array<type intOrBoolOrString> {
                  return [3, true, "yes"];
                }

                pub fnc main(): void {
                  [const number, flag, answer] = mixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnCanUseListBackedValueAndPrefixConstPattern() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed(): [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main(): void {
                  const [number, flag, answer] = fixed();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleReturnRejectsWrongArityAndWrongSlotType() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken(): [int, bool, string] {
                  return [3, true];
                }

                pub fnc main(): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken(): [int, bool, string] {
                  return [3, "not-bool", "yes"];
                }

                pub fnc main(): void { return; }
                """)));
    }

    @Test
    void explicitLetChangesPropagationForRemainingSequenceBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed(): [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main(): void {
                  [const number, let flag, answer] = fixed();
                  flag = false;
                  answer = "no";
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed(): [int, bool, string] {
                  return [3, true, "yes"];
                }

                pub fnc main(): void {
                  [const number, flag, answer] = fixed();
                  flag = false;
                  return;
                }
                """)));
    }

    @Test
    void recordReturnSupportsBothObjectDestructureSpellings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc result(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                fnc prefixed(): void {
                  const {foo, bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                fnc inlineKinds(): void {
                  {const foo, const bar} = result();
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }

                pub fnc main(): void {
                  prefixed();
                  inlineKinds();
                  return;
                }
                """)));
    }

    @Test
    void recordReturnsAndDestructuresRejectMissingOrWrongMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken(): {foo: int, bar: string} {
                  return struct{foo: int}{foo: 5};
                }

                pub fnc main(): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc broken(): {foo: int, bar: string} {
                  return struct{foo: string, bar: string}{foo: "wrong", bar: "x"};
                }

                pub fnc main(): void { return; }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc result(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                pub fnc main(): void {
                  const {foo, missing} = result();
                  return;
                }
                """)));
    }

    @Test
    void equivalentUnionAndRecordOrderingsHaveCanonicalSignatures() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                @Ret<string | int>
                fnc scalar(): int | string {
                  return 3;
                }

                @Ret<{bar: string, foo: int}>
                fnc object(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                pub fnc main(): void { return; }
                """)));
    }

    @Test
    void prefixedPatternSyntaxDoesNotStealFiniteTupleOrRecordTypedBindings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc fixed(): [int, bool, string] {
                  return [3, true, "yes"];
                }

                fnc result(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                pub fnc main(): void {
                  val [int, bool, string] tupleValue = fixed();
                  val {foo: int, bar: string} recordValue = result();
                  stdio.println(tupleValue[0]);
                  stdio.println(recordValue.foo);
                  return;
                }
                """)));
    }

    @Test
    void unionTupleReturnsDestructureWhenEveryAlternativeHasCompatibleArity() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag): [int, string] | [bool, string] {
                  if flag; do
                    return [3, "number"];
                  else
                    return [true, "boolean"];
                  fi
                }

                pub fnc main(): void {
                  const [value, label] = variant(true);
                  stdio.println(value);
                  stdio.println(label);
                  return;
                }
                """)));
    }

    @Test
    void unionRecordReturnsDestructureWhenEveryAlternativeProvidesRequestedMembers() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag): {foo: int, bar: string} | {foo: bool, bar: string} {
                  if flag; do
                    return struct{foo: int, bar: string}{foo: 3, bar: "number"};
                  else
                    return struct{foo: bool, bar: string}{foo: true, bar: "boolean"};
                  fi
                }

                pub fnc main(): void {
                  const {foo, bar} = variant(false);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """)));
    }

    @Test
    void unionDestructureRejectsIncompatibleArityOrMissingMembers() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag): [int, string] | [bool, string, int] {
                  if flag; do
                    return (3, "number");
                  else
                    return (true, "boolean", 9);
                  fi
                }

                pub fnc main(): void {
                  const [value, label] = variant(true);
                  return;
                }
                """)));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag): {foo: int, bar: string} | {foo: bool} {
                  if flag; do
                    return struct{foo: int, bar: string}{foo: 3, bar: "number"};
                  else
                    return struct{foo: bool}{foo: true};
                  fi
                }

                pub fnc main(): void {
                  const {foo, bar} = variant(false);
                  return;
                }
                """)));
    }

    @Test
    void bareDiscardIsRejectedInObjectPatterns() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc result(): {foo: int, bar: string} {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                pub fnc main(): void {
                  const {foo, _} = result();
                  return;
                }
                """));
    }

    @Test
    void unionTupleAndArrayReturnsExecuteThroughRuntimeShapeChecks() throws Exception {
        String program = """
                type Scalar = int | bool | string;

                fnc tupleVariant(bool flag): [int, string] | [bool, string] {
                  if flag; do
                    return [3, "number"];
                  else
                    return [true, "boolean"];
                  fi
                }

                fnc values(): Array<Scalar> {
                  return [3, true, "yes"];
                }

                pub fnc main(): void {
                  const [value, label] = tupleVariant(false);
                  [const number, flag, answer] = values();
                  stdio.println(value);
                  stdio.println(label);
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "union-return-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("true"));
        assertTrue(text.contains("boolean"));
        assertTrue(text.contains("3"));
        assertTrue(text.contains("yes"));
    }

    @Test
    void returnedTupleAndRecordDestructureAtRuntime() throws Exception {
        String program = """
                type FixedResult = [int, bool, string];
                type NamedResult = {foo: int, bar: string};

                fnc fixed(): FixedResult {
                  return [3, true, "yes"];
                }

                fnc result(): NamedResult {
                  return struct{foo: int, bar: string}{foo: 5, bar: "x"};
                }

                pub fnc main(): void {
                  const [number, flag, answer] = fixed();
                  const {foo, bar} = result();
                  stdio.println(number);
                  stdio.println(flag);
                  stdio.println(answer);
                  stdio.println(foo);
                  stdio.println(bar);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "returned-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3"));
        assertTrue(text.contains("true"));
        assertTrue(text.contains("yes"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("x"));
    }

    @Test
    void restDestructuringInfersHomogeneousAndFiniteRemainders() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc values(): Array<int> {
                  return [1, 2, 3];
                }

                fnc fixed(): [int, bool, string] {
                  return [7, true, "tail"];
                }

                fnc acceptsInts(Array<int> values): void {
                  stdio.println(values[0]);
                  return;
                }

                fnc single(): [int] {
                  return [99];
                }

                pub fnc main(): void {
                  const [v, ...rest] = values();
                  acceptsInts(rest);

                  [const first, const ...tail] = fixed();
                  val [bool, string] typedTail = tail;
                  const [flag, label] = typedTail;

                  const [only, ...emptyTail] = single();
                  val [] typedEmptyTail = emptyTail;
                  stdio.println(only);
                  stdio.println(v);
                  stdio.println(first);
                  stdio.println(flag);
                  stdio.println(label);
                  return;
                }
                """)));
    }

    @Test
    void recordRestComputesStaticOmitShapeForBothBindingSpellings() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                type Row = {v: int, label: string, ok: bool};

                fnc row(): Row {
                  return struct{v: int, label: string, ok: bool}{v: 5, label: "x", ok: true};
                }

                fnc acceptsRest({label: string, ok: bool} value): void {
                  stdio.println(value.label);
                  stdio.println(value.ok);
                  return;
                }

                fnc prefixed(): void {
                  const {v, ...rest} = row();
                  acceptsRest(rest);
                  stdio.println(v);
                  return;
                }

                fnc inlineKinds(): void {
                  {const v, const ...rest} = row();
                  acceptsRest(rest);
                  stdio.println(v);
                  return;
                }

                pub fnc main(): void {
                  prefixed();
                  inlineKinds();
                  return;
                }
                """)));
    }

    @Test
    void finiteTupleRestRejectsImpossiblePrefixAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc fixed(): [int] {
                  return [7];
                }

                pub fnc main(): void {
                  const [a, b, ...rest] = fixed();
                  return;
                }
                """)));
    }

    @Test
    void objectRestRejectsDynamicShapeAndRemovedMembers() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc dynamic(string key): void {
                  const value = infer struct{`key`: 1, v: 2};
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                type Row = {v: int, label: string};

                fnc row(): Row {
                  return struct{v: int, label: string}{v: 5, label: "x"};
                }

                pub fnc main(): void {
                  const {v, ...rest} = row();
                  stdio.println(rest.v);
                  return;
                }
                """)));
    }

    @Test
    void parserRequiresRestBindingToBeFinal() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc values(): Array<int> { return [1, 2, 3]; }
                pub fnc main(): void {
                  const [v, ...rest, last] = values();
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc value(): {v: int, label: string} {
                  return struct{v: int, label: string}{v: 1, label: "x"};
                }
                pub fnc main(): void {
                  const {v, ...rest, label} = value();
                  return;
                }
                """));
    }

    @Test
    void restFirstOrMiddleIsAlwaysRejectedForBothSyntaxFamilies() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc values(): Array<int> { return [1, 2, 3]; }
                pub fnc main(): void {
                  const [first, ...rest, last] = values();
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc values(): Array<int> { return [1, 2, 3]; }
                pub fnc main(): void {
                  [const ...rest, const last] = values();
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc value(): {active: bool, label: string} {
                  return struct{active: bool, label: string}{active: true, label: "x"};
                }
                pub fnc main(): void {
                  const {...rest, active} = value();
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc value(): {active: bool, label: string} {
                  return struct{active: bool, label: string}{active: true, label: "x"};
                }
                pub fnc main(): void {
                  {const ...rest, const active} = value();
                  return;
                }
                """));
    }

    @Test
    void staticallyTypedIterableRestUsesDeclaredIteratorShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Vector<T> as
                  let Array<T> items = new Array<T>();

                  pub add(T value): void {
                    self.items.add(value);
                    return;
                  }

                  pub [Symbol.iterator](): Array<T> {
                    return self.items;
                  }
                end

                fnc vector(): Vector<int> {
                  let Vector<int> values = new Vector<int>();
                  values.add(4);
                  values.add(5);
                  values.add(6);
                  return values;
                }

                fnc acceptsInts(Array<int> values): void {
                  stdio.println(values[0]);
                  return;
                }

                pub fnc main(): void {
                  const [first, ...rest] = vector();
                  acceptsInts(rest);
                  stdio.println(first);
                  return;
                }
                """)));
    }

    @Test
    void unionRestKeepsEveryFiniteTailAlternativeStatic() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc variant(bool flag): [int, string] | [int, bool, string] {
                  if flag; then
                    return [3, "short"];
                  else
                    return [4, true, "long"];
                  fi
                }

                pub fnc main(): void {
                  const [head, ...rest] = variant(false);
                  stdio.println(head);
                  return;
                }
                """)));
    }

    @Test
    void restOnlyPatternsPreserveCompleteStaticShape() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc values(): Array<int> {
                  return [1, 2, 3];
                }

                fnc row(): {v: int, active: bool} {
                  return struct{v: int, active: bool}{v: 5, active: true};
                }

                fnc acceptsAllInts(Array<int> values): void {
                  stdio.println(values[0]);
                  return;
                }

                fnc acceptsWholeRow({v: int, active: bool} value): void {
                  stdio.println(value.v);
                  stdio.println(value.active);
                  return;
                }

                pub fnc main(): void {
                  const [...all] = values();
                  const {...whole} = row();
                  acceptsAllInts(all);
                  acceptsWholeRow(whole);
                  return;
                }
                """)));
    }

    @Test
    void restDiscardIsRejectedInSequenceAndObjectPatterns() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc values(): Array<int> { return [1, 2, 3]; }
                pub fnc main(): void {
                  const [..._] = values();
                  return;
                }
                """));

        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc row(): {v: int} { return struct{v: int}{v: 1}; }
                pub fnc main(): void {
                  const {..._} = row();
                  return;
                }
                """));
    }

    @Test
    void borrowedHolderMemberKeepsStaticSequenceRestType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Holder as
                  pub val Array<int> values;
                end

                fnc acceptsInts(Array<int> values): void {
                  stdio.println(values[0]);
                  return;
                }

                fnc inspect(&Holder holder): void {
                  const [first, ...rest] = holder.values;
                  acceptsInts(rest);
                  stdio.println(first);
                  return;
                }

                pub fnc main(): void {
                  val Holder holder = new Holder([1, 2, 3]);
                  inspect(&holder);
                  return;
                }
                """)));
    }

    @Test
    void restDestructuringExecutesWithoutRuntimeTypeDiscovery() throws Exception {
        String program = """
                type Row = {v: int, label: string, ok: bool};

                fnc values(): Array<int> {
                  return [10, 20, 30];
                }

                fnc row(): Row {
                  return struct{v: int, label: string, ok: bool}{v: 5, label: "rest", ok: true};
                }

                pub fnc main(): void {
                  const [head, ...tail] = values();
                  const {v, ...other} = row();
                  stdio.println(head);
                  stdio.println(tail[0]);
                  stdio.println(tail[1]);
                  stdio.println(v);
                  stdio.println(other.label);
                  stdio.println(other.ok);
                  return;
                }
                """;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "rest-destructure.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();

        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("10"));
        assertTrue(text.contains("20"));
        assertTrue(text.contains("30"));
        assertTrue(text.contains("5"));
        assertTrue(text.contains("rest"));
        assertTrue(text.contains("true"));
    }

}

# Proposal: declaration kinds, `typeof` type queries, and `match<T>`

**Status:** design proposal, **not implemented**. This file must not be read as evidence that these constructs currently parse or execute. See draft PRs #184 (storage-free traits), #296 (expression matches), and #404 (explicit `do match` and arm-local `return`). Integrate on the newer canonical `match ... over / on ... / end` grammar, not by regressing to an earlier syntax.

## 1. Distinct declaration and value categories

- `define trait Name as ... end`, `define interface Name as ... end`, `define class Name as ... end`, and an eventual nominal `define struct Name as ... end` introduce declared entities/types; their declaration keywords must not be wrapped in `define type ... as trait`.
- A function declaration or lambda introduces a **value** with a **callable type**. The value/closure is not a new nominal type.
- `type Alias = TypeExpression;` is a transparent alias. Alias resolution must not invent a new runtime identity. `type HN = HasName;` is valid.
- `type F = foo;` is invalid when `foo` denotes a function value. `type F = typeof foo;` queries the type, not the value.
- For AOT, type expressions are compile-time-only and must not evaluate arbitrary runtime code. Avoid Java reflection as a semantic authority.

The current main branch parses `define trait` and `define interface` to the same `InterfaceDecl` representation. It also parses interface fields as `name: string;`, not `requires name: string;`. Split trait/interface declaration kind as part of the four-way type integration before assigning different semantics to them. In the storage-free trait design (#184), `requires name: string;` may represent a **required host property**, never trait-owned storage; define this distinctly from a field declaration. A structural shape contract should generally use an interface.

## 2. `typeof` in type position

Two operators with different syntax but the same target category:

```ores
// Existing grammar: explicitly declare a function-signature type.
type Predicate = typeof fnc(bool value) => bool;

// Proposed: compile-time query over a callable value.
fnc stringify(int value): string {
  return "value";
}
type Stringifier = typeof stringify;
```

`typeof stringify` must normalize to the compiler's callable-signature type, including argument and result types, async/trap/ownership/borrowing effects where applicable. It must not denote the unique identity of `stringify`. A different function with the same compatible signature has that callable type too.

The parser presently expects `fnc` immediately after `typeof` in type position. Add an AST `TypeQuery(value-reference)` or equivalent, not a magic string embedded in `TypeRef.name`. Resolve names using the same lexical/import/actor-isolation constraints as ordinary value references. Never resolve unrelated runtime values by scanning all declared functions.

Hardening:
- Reject unresolved, inaccessible, or cyclic queries.
- Reject ambiguous overload sets until a signature-selecting syntax is explicitly designed (rather than silently choosing the first overload).
- Reject unspecialized generic callable references until generic polymorphic values are supported.
- Reject routine/actor entry points if they cannot be referenced as first-class callable values; do not quietly widen them to ordinary function values.
- Do not evaluate `typeof (sideEffect())`. If an expression-query form is introduced, it must use static expression typing only and guarantee zero evaluation.
- Do not infer runtime callable identity, closure capture identity, or memoization keys from a type query.

`typeof` is **not** a JavaScript-like expression yielding a string or runtime type object. If runtime introspection is needed, design a separate capability-checked operator such as `typeid(value)`.

## 3. Function patterns and aliases

Proposed canonical arms:

```ores
type Foo = typeof foo;

const string result = match<string> candidate over
  on typeof foo f -> { return f(3); }
  on _ -> { return "not callable with that signature"; }
end;
```

`on typeof foo f` and `on Foo f` must mean exactly the same callable signature predicate when `Foo` aliases `typeof foo`. If both are used sequentially, the later unguarded arm is unreachable and must be diagnosed. Neither spelling means `candidate is the **specific** function object foo`; a separate value-identity pattern would be required if identity matching is desired.

A callable signature test is not a nominal class test. Do not coerce it into `Java instanceof` or use a mutable host reflective proxy. Function patterns need a reifiable Oreslang callable descriptor validated consistently by the static type checker, AOT admission, and interpreter/native lowering. If reification is absent (for instance erased type parameters or opaque imported host callable types), reject the runtime pattern rather than silently matching all functions or matching none.

Async vs sync, `trap`/Option result effects, `pure`/capability effects, mutable/borrowed parameters, arity and return variance must be included or explicitly constrained in callable compatibility. Never let a weaker runtime signature bypass a stronger static constraint.

## 4. `match<T>` is an **expression result constraint**

Canonical intended form:

```ores
const string label = match<string> candidate over
  on typeof foo f -> { return f(10); }
  on _ -> { return "unknown"; }
end;
```

The generic argument constrains the **result**, not the scrutinee. The scrutinee remains independently typed. The subject expression must be evaluated once, in source order. Every reachable arm must complete with a result assignable to `T`; every control path must be covered (or be `never`/divergent). `match<T>` is not a function template invocation and creates no polymorphic runtime match object.

**Arm result syntax:** keep the existing slim arrow and the explicit *arm-local* `return value;` planned in #404. An optional `-> T { ... }` annotation would be redundant with `match<T>` and can be introduced later as a checked per-arm assertion; do not require it. `return` inside a value-match arm targets the match-result scope, not the enclosing function. A terminal `return;` is invalid for non-void `T`. Nested/early returns must follow the explicit arm-local control-flow rule from #404.

For side effects use `do match candidate over ... end` from #404. Do not overload `match<void>` to fabricate a runtime void value. `match<T>` used as a value must reject `T = void`. The companion `do match` does not produce a value. Reject `const void x = ...`.

**Type inference:** `match subject over ... end` as an expression may infer a least upper bound when all arms have compatible values, but `match<T>` provides a stronger expected type for contextual inference (including lambda literals and Option.none()). Return type inference must not downgrade ownership, borrow, or capability qualifiers.

**Control-flow proof:** canonical `match ... over` has source-order priority on current main but retains exhaustiveness and unreachable-arm checks. The older grammar/documentation describes some bare `match` spellings as exclusive-by-default. Align the grammar/documents/tests with whichever branch is landed: preserve `match first` only where genuinely useful, diagnose shadowed arms, and do not silently switch priority/exclusivity by adding a type argument.

## 5. `void`, aliases, and unit

```ores
type NoResult = void;          // valid as a type alias
fnc log(): NoResult { return; }  // valid result position
// type NoResult = typeof void; // invalid: void is a type, not an expression
// const NoResult v = ...;      // invalid: void has no runtime value
// const NoResult v = match<NoResult> ...; // invalid
```

`void` denotes absence of a returned value. Do not unify it with an empty tuple `()` or with `Option.none()`. If a first-class zero-sized result is needed, introduce a separate `Unit` type with an explicit inhabitant and ABI representation; then `match<Unit>` may be bound to a value. This decision must be reflected in function types, generics, tuples, arrays, and FFI.

## 6. Admission / regression matrix

Positive tests:
1. `define interface HasName as name: string; end` and `type HN = HasName;`.
2. `type NoResult = void;` as a callable result; `type F = typeof fnc(int) => string;`.
3. `type Foo = typeof foo;` with a non-generic non-overloaded callable.
4. `match<string>` with mixed callable/noncallable arms, guarded fallbacks and explicit arm-local `return`.
5. Alias spelling and inline type-query spelling yield identical matcher predicates; qualified/imported aliases retain access checks.
6. AOT and interpreted/JIT results agree across identity, structural compatibility, and callable effect metadata.

Negative tests:
1. `define type Foo as trait`, `type X = foo`, `typeof void`.
2. Alias recursion and value/type namespace confusion; inaccessible/private and actor-boundary queries.
3. Ambiguous overload / unspecialized generic `typeof` references.
4. Two overlapping `on Foo f` / `on typeof foo f` arms; wildcard arm before typed arm.
5. Erased/unreifiable callable tests, mismatched async/effects/borrow parameter ownership.
6. `match<void>` as value, `const void`, non-void typed arm with `return;`, fall-through without return, missing fallback.
7. Match subject evaluated twice, capture escape, match-arm borrow escaping, compile-time `typeof` executing side effects.
8. Cross-backend equivalence: JVM/Truffle, AOT, hybrid, and isolate.

## 7. Stacking / integration sequence

1. Land or reconcile newer four-way declaration-type changes (#184 and its dependencies), retaining a distinction between interface contract and storage-free trait behavior.
2. Reconcile existing match-expression and `do match` drafts (#296/#404) with the canonical `over/on` grammar and newer matching work (#398). Do not reimplement competing match expression ASTs.
3. Add AST/type-resolution support and tests for `typeof value` while preserving existing `typeof fnc(...) => T`.
4. Add reifiable callable type-pattern support and two-arm alias equivalence/unreachability tests.
5. Add `match<T>` as a contextual result-type argument on the existing expression-match machinery, with exhaustive control-flow and void/Unit checks.
6. Update formatter and marketing examples only when corresponding behavior is compiler-tested. Validate each landing head by the exact-tree cross-org CI mirror process when Actions cannot allocate runners.

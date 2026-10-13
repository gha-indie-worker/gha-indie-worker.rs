# Algebraic data types (ADT): nominal sums, products, and exhaustive matches

Status: **incremental source implementation on Java PR #623**, not an LLVM
release/parity claim. Last reviewed: 2026-10-13.

## Representation and cardinality

- Product (AND): a tuple/record/struct value includes *every* member.
  For finite component domains, `|Tuple[A,B]| = |A| * |B|`.
  Example: `Tuple[bool, u8]` has 2 * 256 = 512 mathematical values,
  assuming an exact `u8` type; current source/wire integer-width
  preservation must be checked separately before actor serialization.
- Nominal sum (OR): `define enum` introduces a type whose value has
  **exactly one** distinguished case and that case's typed payload.
  `|E| = sum_case |payload(case)|`, taking nullary payload as Unit (1).
- `Option<T>`: `1 + |T|` for `None | Some(T)`.
- `Result<T,E>`: `|T| + |E|` for `Ok(T) | Err(E)`.
- Bottom/Never has zero inhabitants; Unit has one inhabitant. Their final
  surface spelling, `void`/return distinction and ABI are **not** established
  by the enum implementation and must not be silently assumed.
- Function spaces are `B^A` mathematically for total extensional
  functions between finite sets; runtime closures, effects and identity
  are distinct operational semantics.

A payload-bearing case is **not** the same as its payload value, tag
or its constructor descriptor. `E.A("x") != E.B("x")` even if the two
cases have the same string payload type and identical bytes. Matching
must dispatch on nominal owner and case/tag first.

## Current executable Java source syntax

```ores
define enum Status as
    Loading
    Active: Tuple[bool, string]
    Failed: string
end

define match pattern Messages as
    ...Status
end

fnc describe(Messages status): string {
    match status over
        on Status.Loading -> { return "loading"; }
        on Status.Active as (ok, description) -> { return description; }
        on Status.Failed(reason) -> { return reason; }
    end
}
```

The canonical `on Status.Active as (ok, description)` form and
`on Status.Failed(reason)` both destructure one enum payload.
The source payload is a **single** typed value; its structure can itself
be a product. A nullary case matches without parentheses.

`define match pattern` builds a **closed, type-only case domain**:
it does not allocate a value, add a new runtime discriminator,
flatten payload types or create an implicit tagged union. `...Status`
expands all known enum cases at compile time in declaration order.
Explicit cases and spreads may be mixed; duplicate cases are errors.
Only enum namespaces are spreadable in the current implementation.
The older spelling `define pattern` remains accepted.

Do not confuse this with an arbitrary structural/type union:
`type T = A | B` and generic `define match pattern` over primitive,
record or open structural types are separate feature decisions.
Matching an open union demands either a proven complete partition
or an explicit unguarded fallback. No implementation may infer
disjointness from identical/overlapping structural shapes.

## Static matching invariants

1. Scrutinee evaluation occurs exactly once; the first matching arm
   executes in canonical `match ... over` syntax.
2. Every qualified enum case must belong to the *static* scrutinee's
   nominal enum or named closed case domain. A misspelled, foreign,
   or unregistered case must fail **at compile time**, even with a
   wildcard fallback.
3. Nullary cases have zero payload patterns; payload cases have
   exactly one, which can be a recursively nested tuple/pattern.
4. A pattern must be type-correct for its case payload. Pattern
   bindings have lexical arm scope and must follow move/borrow rules.
5. Exhaustiveness is about **all values**, not merely a list of case
   names: `Some(7) + None` does not cover `Some(8)`; likewise
   `Active((true, _))` plus all other enum cases does not cover
   `Active((false, _))`.
6. Guards cannot be counted as unconditional coverage. An unguarded
   wildcard/binding is a complete fallback. Dead arms following a
   subsuming unguarded arm must be rejected.
7. Proof failures must fail closed. Java now admits *combined*
   partial coverage when it can enumerate the complete finite domain
   (maximum 4096 inhabitants; recursion depth capped at 16). Examples:
   `Some(true)`, `Some(false)`, `None` for `Option<bool>`, boolean
   tuple cross-products, and finite enum payloads. It rejects arms
   collectively made unreachable by earlier unconditional arms.
   Infinite, oversized, opaque and unresolved domains use a
   conservative constructor/payload check instead. Java constructs the
   finite witness set only once per match and maintains incremental
   unguarded-arm coverage. It caps match bodies at 4096 arms to prevent
   unbounded compiler work. These checks are not yet a general symbolic
   decision-matrix solver.
8. Enum growth changes the resolved domain of `...Enum`; downstream
   exhaustive match compilation must be invalidated. Wire schema
   compatibility and deployed sender/receiver negotiation are
   separate requirements.

## Local numeric payloads versus actor-wire codecs

A local enum case can carry a supported exact-width integer such as `u8`,
`i8`, `u16`, `i16`, `u32`, `i32`, or `u64`, including validated
tuples/lists of those scalars. The local runtime's constructor validator
checks signedness, range, scalar type, nested field arity and a bounded
object graph. An out-of-range numeric constructor **literal** is also
rejected at compile time. The current generalized source type IR still
erases numeric width, so nonliteral arithmetic/assignment is not yet a
complete static proof of exact-width safety; runtime construction remains
a mandatory checked boundary.

**Do not confuse local admission with portable actor-message admission.**
`u8`-bearing enum cases have no MessagePack v2 width-preserving shape
today and `TaggedMessageContracts.verify` must still reject them.
Only separately verified wire shapes can enter typed actor mailboxes.
Adding a local enum payload must never silently change an actor schema.

A local enum may also **nest another local enum** that carries such a
non-wire width (including through a homogeneous `List<Inner>`), even when
the inner enum is forward-declared. This does not promote either case to a
portable actor codec; the outer case retains `payload == null` in the wire
registry while retaining its source payload type. Recursive list-backed
enums are accepted as finite local values with bounded graph validation.
Direct/unboxed recursive enum cycles remain rejected by the dependency
checker. An explicit `Box<T>` is not yet proof of an executable boxed
value: allocation, move/drop and serialization still need implementations.


Named enums/patterns may not shadow builtin or ordinary declaration type
identities, and they are not implicitly generic. Unsupported source enum
payloads (including `Option<T>`/`Result<T,E>` *nested inside a user enum*,
unknown classes, and unresolved numeric `f32` semantics) currently fail
closed until their runtime representation, static checks and codecs are
implemented. Standalone `Option` and `Result` remain ADT match subjects.

## Recursive ADTs and ownership

- Reject direct/mutual infinitely-sized **inline** recursion, including
  recursion through tuples/fixed arrays; finite `Box<T>` indirection
  is not automatic proof of safe runtime execution/serialization.
- Preserve owner/case/tag identity and recursively validated payload.
  An unboxed nested enum value may have a local representation before
  it has an actor-wire codec. A missing codec must be rejected, not
  represented as a nullary case.
- Destructuring a move-only payload must not create two owners.
  Borrowed fields cannot outlive their root enum/tuple. A mutable
  enum payload must not be shared across actor isolation boundaries.
- Equality and hashing depend on nominal identity and recursively
  equality-comparable payloads; hash-map admission needs immutable
  `Hash`/`Eq` keys. Do not hash only wire tags.

## Ordered versus exclusive match safety

Canonical `match value over` is an ordered first-match decision and may
contain intentionally overlapping patterns (subject to unreachable-arm
diagnostics). The legacy bare `match value` form is **exclusive**: exactly
one non-fallback arm may succeed for each value. The Java runtime already
enforces this with an ambiguous-match exception, so its type checker must
prove explicit arms pairwise disjoint rather than treating them as ordered.

Distinct registered nominal enum case tags, disjoint finite literals,
and disjoint tuple/product projections can establish this proof.
Numeric literals must compare with runtime equality semantics:
`1` and `1.0` can match the same value, including nested literal
constructor patterns. Guard-range disjointness is permitted only for
identical patterns binding the same projection. Because the proof uses
floating-point bounds, wide integer guard literals above exact binary64
range (absolute value greater than 2^53) cannot prove disjointness.
Opaque, imprecise, or overlapping cases fail closed; use `match ... over`
when precedence is intentional.

Both parser and type checker limit match bodies to 4096 arms. Parser-side
admission prevents oversized source programs from forcing AST allocation
before the semantic pass. These limits and guard restrictions are language
safety contracts, not proof that full Java/Graal or LLVM tests have passed.

## Executable differential corpus

`contracts/v2/adt-conformance/cases.toml` is the versioned canonical
Java/LLVM fixture manifest; `AdtConformanceCorpusTest` runs it under
GraalVM (with compiler rejection and guest-stdout expectations). The
manifest explicitly marks LLVM `pending` until native lowering and
execution are verified. The corpus and exact-source Git tree identity
are necessary integration assets, **not** evidence of passed tests.
Recent external workflow runs failed with `startup_failure` before any
JVM/Maven/JUnit step. The Java PR stays draft and must not be merged on
source review alone.

## Required conformance matrix before language-level ADT parity

- Positive: nullary/payload enum cases, two distinct cases sharing
  one payload shape, nested enum/tuple/Option/Result values, all
  exhaustive case branches, wildcard fallback, tuple destructuring,
  forward enum declarations and enum spreads.
- Negative: duplicate names or tags, empty/invalid case definitions,
  unknown/foreign variants, wrong payload arity/type, duplicate/dead
  arms, missing case, partially covered payload, guard-only coverage,
  bad enum/pattern recursion and nonserializable actor payloads.
- Run **real Oreslang programs**, not only AST parsers, on Java/GraalVM
  and LLVM, and compare stdout, diagnostics, ownership failures,
  wire bytes and schema evolution fences.
- Run property/fuzz tests against the pattern decision DAG; for finite
  small domains enumerate every inhabitant and compare a reference
  interpreter's dispatch with the typechecker's proof.
- Preserve ABI stability for native tagged payload layout, drop glue,
  alignment, niche optimizations and every case's wire identity.
  Native lowering of the general tuple/string/recursive ADT program
  above is **not yet verified**.

This document is a language contract and work gate, not evidence that
all combinations are already implemented or tested.

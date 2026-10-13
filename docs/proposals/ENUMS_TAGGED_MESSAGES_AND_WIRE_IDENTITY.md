# Oreslang tagged enums and actor message patterns

**Implementation status — October 11, 2026:** The Java/GraalVM parser, semantic checker, source evaluator, actor mailbox integration and MessagePack v2 codec now implement the **initial closed, top-level enum/pattern subset**. Funded [exact-source Maven CI](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38171602084) compiled 79 mirrored Git blobs and passed 45 selected JUnit tests, including an isolated actor sending a tagged message. Native C++17 nominal wire parity passed [funded CI](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38171435534). Full LLVM compiler and native actor mailbox lowering remain unimplemented. Both PRs are DRAFT.

## Canonical syntax

```ores
define enum Status as
    Message1: Tuple[bool, string] = 1
    Message2: string = 2
    Message3: FixedArray[string, string, string] = 3
    Ready = 4
end

define pattern Messages as
    Status.Message1
    Status.Message2
    Status.Message3
end

define isoactor WorkerActor as
    receive(ActorMail<Messages> mail): void {
        // The receiver owns its decoded tagged message.
        self.end();
        return;
    }
end

pub async routine main(): void {
    const worker = await spawn WorkerActor();
    worker.send("hello" as Status.Message2);
    await worker.get_done();
}
```

Each **enum** is a nominal type/namespace; each **case** has an optional payload type and a positive 32-bit integer wire tag. Bare payload-bearing `Status.Message2` denotes the case, not an instance; `Status.Message2("hello")` or `"hello" as Status.Message2` constructs a checked value. `Status.Ready()` constructs a payloadless value. The current subset does not yet support bare `Status.Ready` as a value.

A **pattern** is a closed, nonconstructible type-only set of qualified enum cases. Inside `define pattern`, each line is implicitly an alternative: **no `|`**. Commas and semicolons are optional separators. The ordinary union type syntax `A | B` remains separate and is not interchangeable with this declaration grammar. `ActorMail<Messages>` and `ActorRef.send` enforce membership of tagged alternatives.

## Stable tags, schema and actor isolation

Implicit tags begin at `1` and increment; explicit positive tags may reserve gaps. Tags must be unique and **strictly monotonically increasing** within their enum. Unknown cases, missing codecs, wrong payload types, invalid tags and out-of-domain messages fail closed. Published schema tags must eventually be frozen in a generated registry/manifest; do not treat recompiling a reordered enum as an automatic wire migration.

SHARED, PRIVATE/isolated and UNTRUSTED actors never need to share enum instances, JVM classes, memory pointers or mutable references. Compilers provide compatible nominal enum/domain schemas. The sender creates the runtime-owned MessagePack frame, and the receiver reconstructs a new tagged value on its exclusive actor turn.

The first executable canonical MessagePack v2 representation is:
```
[
  2,
  case-and-domain-scoped compiler schema_id,
  bin8(32, SHA256("oreslang.actor.shape.v2/" + canonical_payload_shape)),
  [enum_name, positive_integer_tag, recursively_serializable_payload]
]
```
For a payloadless case the inner sequence contains `[enum_name, tag]`. The current Java codec selects decoders exclusively from the **compiled receiver's closed domain**, not from arbitrary untrusted wire type names. The SHA256 shape digest is a type-binding commitment, **not** sender authentication or an integrity MAC. The exact `Status.Message2("hello")` bytes are shared in `contracts/v1/design/tagged_message_vectors.json`, independently executed by both Java and native C++17.

## Implemented now

- Parser/AST: top-level `define enum` and `define pattern` with newline alternatives and checked monotonic tags.
- Type checker: source variants and explicit `as` construction, recursive supported scalar/collection payload eligibility, and typed `ActorRef.send` pattern admission.
- Java evaluator: namespace/case constructors, checked variant casts, nominal runtime matching primitives, automatic compiled `ActorMail<Messages>` codec binding.
- Java actor runtime: binary frame queued without cross-actor heap alias, decoded on recipient turn, host CONTROL messages separate.
- Native: independent C++17 tagged wire encoding/decoding building on the MessagePack v2 codec; exact binary parity fixture and optional CMake/CTest target.

## Not yet implemented or verified

- Full LLVM Oreslang source parser/type checking, actor `send/receive` lowering, native scheduler integration, and matching/exhaustiveness proof; the passing native codec is **not** evidence of those features.
- Generic/imported variants, package-qualified name resolution across arbitrary code units, immutable published schema registry/lockfile, compatibility migrations and unknown-version/tag negotiation.
- Derived `@Serializable` classes, Option/Result, user-defined records, arbitrary numeric widths, recursively tagged enum payloads, and automatic schema evolution.
- Comprehensive tagged match exhaustiveness/unreachability tests, cross-runtime live process or WASM delivery, fuzzing, cancellation/memory lifetime stress, full native-image and LLVM CMake/CTest suites.
- `rt copy` optimization: MessagePack stays the default; copy is permitted only with identical snapshot/isolation semantics and proof of performance gains.

**Merge policy:** keep [Java PR #623](https://github.com/ores-truffle-oreslang/oreslang-source.java/pull/623) and [LLVM PR #155](https://github.com/ores-truffle-oreslang/oreslang-source.llvm/pull/155) as drafts until the production gates pass.

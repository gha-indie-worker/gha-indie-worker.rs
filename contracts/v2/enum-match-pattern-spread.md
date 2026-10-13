# Oreslang closed `match pattern` declarations and enum spreads

Status: implemented source-grammar/closed-domain expansion in Java/GraalVM and LLVM **draft branches** (2026-10-11); release/runtime parity still gated by exact-head tests.

## Canonical syntax

```ores
define enum Status as
    Message1: Tuple[bool, string]
    Message2: string
    Message3: string
end

define match pattern Messages as
    ...Status
end

define actor Worker<messages=Messages> as
    @Implements
    receive(Message m) {
        match m over {
            on Status.Message1 as (ok, text) { self.respond(text); }
            on Status.Message2 as text { self.respond(text); }
            on Status.Message3 as text { self.done(); }
        }
    }
end
```

The `define match pattern` spelling is canonical. Existing `define pattern` remains accepted for backwards compatibility in this milestone. Changing formatter output is separate work; do not claim formatter parity. The language-level actor/tuple/string/respond/done example is a **target**; LLVM currently compiles/executes only its narrower payloadless/integer subset. Java currently has its own source actor mailbox and typechecking grammar; full example parity remains to be proved.

The `...Status` is a **compile-time type-domain spread**, never a runtime array, dereference, method invocation or object-copy operation. It expands in enum declaration order to:

```ores
define match pattern Messages as
    Status.Message1
    Status.Message2
    Status.Message3
end
```

Each case retains nominal enum owner, variant name, numeric wire tag and recursive payload type descriptor. The compiler must not collapse `Message2:string` and `Message3:string` by their matching shape.

### Soundness and stability

- Expansion is resolved **after collecting all root declarations**, so patterns may precede their referenced enums. Imported/namespace-qualified spreads are not yet admitted by these draft implementations.
- The spread can be mixed with explicit qualified cases or other enum spreads. A duplicate **resolved case** is always a compile error; never silently deduplicate.
- Only an **enum namespace** is a valid spread operand. `...OtherPattern`, `...Unknown`, `...Status.Message1`, cyclic/recursive transitive domain macros and runtime values are not allowed.
- Expanded domains are ordered lists of complete enum cases, with the compiler's existing 4096-case upper bound. The checks apply to expanded size, not only the number of spread tokens. Existing numeric tags remain positive signed 32-bit values and are validated independently.
- When an enum adds a case, recompiling a spread-bearing pattern automatically includes the new case. A receiver's exhaustive match must now handle that case; compilation must fail if it does not. This is not a guarantee for already-deployed binaries: downstream builds must be invalidated/recompiled.
- Enum schema changes do **not** automatically migrate network protocols. Adding a new case changes the closed-domain message contract; compatibility must be checked against a pinned schema manifest and a deliberate negotiated version.
- Wire identity must be identical for explicit and spread spelling of the **same expanded pattern**. The case-and-domain hash derives from the *resolved cases* and the named pattern, never the `...` source token.
- Actor messages still require recursively serializable payloads and immutable cross-actor ownership boundaries; no raw host object, pointer, or capability is smuggled by enum spread.
- A `HashMap<Status, Status>` contains *constructed instances*. Variant constructors/descriptors are not implicitly `Status` values when a payload is required. See `contracts/v2/enum-nominal-values-and-map-semantics.md`.

### Cross-language rationale

Kotlin ordinary enum constants are singleton values, and sealed classes/interfaces carry distinct payload records; `when` is exhaustive for closed hierarchies. C# traditional enums are numeric constants, while preview union types add exhaustiveness for closed alternatives. Rust enums carry arbitrary variant data and `match` requires complete coverage; hash-map keys require `Eq + Hash`. Java `EnumMap` accepts standard enum constants as array-indexed keys, but Java sealed records resemble data-carrying tagged unions more closely. Erlang atoms and tagged tuples can be map keys and actor messages, but Erlang maps are dynamically typed terms rather than a compile-time closed sum.

Primary references:
- https://kotlinlang.org/docs/enum-classes.html
- https://kotlinlang.org/docs/sealed-classes.html
- https://learn.microsoft.com/en-us/dotnet/csharp/programming-guide/enumeration-types
- https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/union
- https://doc.rust-lang.org/book/ch06-01-defining-an-enum.html
- https://doc.rust-lang.org/std/collections/struct.HashMap.html
- https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/EnumMap.html
- https://www.erlang.org/docs/26/man/maps.html

## Verification gates

1. Java parser + `TaggedMessageContracts.verify`: expansion, forward refs, duplicate/malformed spreads, 4096 limit, implicit tags, exact MessagePack v2 bytes equal to manually enumerated cases, actor send type admission.
2. LLVM parser admission: identical expanded ordered cases and typed shapes; reject missing/duplicate cases; source guest compiled/executed int-variant actor using the spread; compiler must reject a new enum variant not handled by the receive match.
3. Full Java/GraalVM and LLVM source suites, Native Image AOT/hybrid, native ASan/UBSan, and cross-backend binary fixture parity for exact commit SHAs.
4. Before full release claim, extend LLVM guest source lowering to tuple and string payloads, and verify real `self.respond`/`self.done` and execution-context/lifecycle semantics on both backends.
5. Schema-lock negotiation, stale remote sender compatibility and hostile wire fuzzing before network actor transport.

No green CI result is inferred from parsing or docs alone.

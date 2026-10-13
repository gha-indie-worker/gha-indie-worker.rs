# Nested nominal enums and explicit recursion (draft language contract)

**Scope:** Oreslang Java/GraalVM, LLVM; 2026-10-11. This contract separates
local value/type admission from portable actor transport. It does not assert
that boxed values, LLVM nested guest lowering or nested wire codecs exist yet.

## Ordinary enum nesting is composition, not a metatype

```ores
define enum Bar as
    Message1 = 1
end

define enum Foo as
    Message1: Bar
end

pub routine main(): void {
    val bar = Bar.Message1();
    val foo = Foo.Message1(bar);
    return;
}
```

A `Bar.Message1()` is a *constructed nullary Bar value*; its numeric
discriminator `1` is just a stable representation detail. A
`Foo.Message1(Bar.Message1())` is a *constructed Foo value holding a
constructed Bar value*. It is never equivalent to `Foo.Message1(1)`, nor
to `Bar.Message1()` alone. Both variants may have tag 1 without aliasing,
because their nominal owners differ.

Source variant construction must check the payload's **nominal owner** and
reject any wrong owner or raw tag. Enum-case references/constructors without
payload are not runtime enum values. Existing Java guest spelling for a nullary
case is `Bar.Message1()`. Enum values compare equal only when owner, variant,
tag and recursively compared owned payload all match. Equality must not
degrade to Java host reference identity.

## Recursive types

```ores
define enum Foo as
    Empty
    More: Foo
end
```

This *direct inline self-cycle* has infinite inline size under the language's
by-value semantics, even though a JVM host record could itself contain a
reference. Reject at compile time. Mutual inline cycles
`Foo -> Bar -> Foo`, including nested fixed tuples or fixed arrays, must
also be rejected. The check is independent of declaration order.

```ores
define enum Foo as
    Empty
    More: Box<Foo>
end
```

Here `Box<Foo>` is **explicit indirection for type-size analysis**. Only a
fixed-size owned box handle appears inline; a heap value may terminate in
`Foo.Empty`. This syntax may be admitted at declaration/type graph level
without yet providing runtime allocation/transfer, pointer aliasing, recursive
serialization, or map hashing. Likewise, a homogeneous `List<Foo>` has
finite inline size and can terminate with an empty list. These are different
from inline `Tuple[Foo]` and `Option<Foo>`, which still require storage
for a `Foo` value in the nonempty case and therefore do not by themselves
break the inline cycle.

A definition containing *only* `More: Box<Foo>` has finite layout but no
finite, terminating constructor. This is an **inhabitedness/productivity**
question separate from layout recursion. For now, do not claim the compiler
diagnoses every such uninhabited recursion. A future linter may identify
strongly connected productive cycles and require a terminating alternative for
serializable recursive data. Cyclic host pointer graphs are never implicitly
authorized by `Box`.

## Source and wire phases

1. Declaration graph: resolve nominal enum names and their payload types,
   reject duplicate enums, unknown enum operands and all inline-size cycles.
2. Local value typing/execution: allow finite nonrecursive nested values.
   On Java/GraalVM, nominal constructors and value equality are executable.
   On LLVM, typed enum references currently have parser/analysis admission
   only; guest-value construction is not implemented for nested enums.
3. Actor serialization: require **separate** capability to encode recursively
   nested tagged payloads. A local-only `Foo.Message1: Bar` case carries a
   non-null source type but NO portable wire shape. It must never be
   mistaken for a payloadless variant. Reject it in source actor mailbox
   contracts and host codec constructors until both backends support the
   exact same canonical encoding.
4. Recursive boxed transport (future): require maximum nesting depth, total
   nodes, maximum bytes, cycle detection and immutable ownership snapshots,
   plus schema versioning and hostile-input fuzz tests before admitting
   `Box<Foo>` across actor boundaries.

A nested codec should preserve each enum's own owner, variant/tag and nested
payload, not flatten to an integer. The existing MessagePack v2 envelope and
exact golden vectors remain untouched in this milestone. Future nested wire
format requires an explicit cross-backend schema with unknown-case rejection,
type/shape binding, depth/element quotas, denial-of-service checks and
compatibility/version negotiation.

## Verified focused gates

- Java local nested construction, wrong-owner type rejection, real guest
  equality execution, and actor wire admission negative tests.
- LLVM nominal EnumRef parser, canonical `Box<Foo>`, unknown-name and
  direct/mutual inline recursion rejections, plus actor wire admission
  negative tests.
- Existing exhaustive 3-enum dependency graph corpus (512 graphs) remains
  part of both backends' parser/type regression suites.

**Non-goals:** automatic pointer interpretation; implicit null; a boxed enum
allocator; nested tagged MessagePack; LLVM nested guest actor execution; full
recursive `HashMap<Enum,Enum>` support. Those require separate implementation
and verification.

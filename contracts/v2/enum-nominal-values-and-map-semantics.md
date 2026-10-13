# Oreslang nominal enum values, actor variants, and maps

Status: normative **design contract**, not a claim of completed source/compiler parity.
Target backends: Java/GraalVM and LLVM. Last audited: 2026-10-11.

## Three orthogonal properties

A case declaration has (1) **nominal identity** `Status.Message2`,
(2) optional **payload type** `string`, and (3) a stable positive signed
32-bit **wire tag** `2`. The payload is a type, NOT the enum value; the
tag is an ABI discriminator, NOT the runtime value. A typed enum is a nominal
sum type, never a union of raw payload types and never an alias for `int`.

```ores
define enum Status as
    Message1: Tuple[bool, string] = 1
    Message2: string = 2
    Message3: string = 3
    Ready
end

const one: Status = Status.Message1((true, "foo"));
const two: Status = Status.Message2("hello");
const three: Status = Status.Message3("hello");
```

`two != three` even though their payloads and shapes are equal. `one != 1`,
and `Status.Message2("x") != "x"`. Matching dispatches on case identity
FIRST, then checks/decodes and destructures the declared payload. Tuple
destructuring does not convert the enclosing enum into a tuple. Patterns are
closed type-only sets and do not construct enum instances.

- `Status.Message1` in a variant/pattern declaration is a **case reference**,
  not an instance of `Status`. With an expected `Status` value, a payload
  case must be constructed: `Status.Message1((true, "foo"))`.
- Normative design: a *nullary* case, such as `Status.Ready`, may be used
  as the singleton value. `Status.Ready()` may remain a compatible spelling.
  **Java draft currently requires `Ready()`; LLVM supports bare nullaries in
  its narrow executable subset.** Do not advertise full parity yet.
- Explicit positive numeric `= N` pins the wire tag. Implicit numeric tags
  start at 1 and increment after an explicit value. The implicit and explicit
  `1,2,3` declarations produce the same ABI. Duplicate, out-of-order,
  zero, negative, or out-of-range tags are invalid. A published enum needs a
  schema lock; source reordering is not an allowed silent migration.

## String-valued `= "one"` is a separate design question

This is **not yet supported by either production compiler**:

```ores
define enum Status as
    Message1: Tuple[bool, string] = "one"
    Message2: string = "two"
    Message3: string = "three"
end
```

If admitted, the string must be a distinct optional **associated code/label**,
not the payload value and not the binary discriminator. The case would still
have wire tags 1, 2, 3; `Status.Message2("hello")` carries `"hello"`, not
`"two"`. The code could be exposed as `Status.Message2.code` and governed by
an explicit change/evolution policy. An unambiguous future spelling such as
`@Code("two")` is preferable before locking this surface grammar, especially
because numeric `= N` already reserves wire tags. **Until that decision,
reject string-valued assignments rather than reinterpreting them as payloads
or sending strings in place of nominal enum values.**

## Maps: distinct from associated tags and payloads

Normative semantics when source `HashMap<K,V>` supports nominal values:

```ores
const m = new HashMap<Status, Status>();
m.put(
    Status.Message1((true, "foo")),
    Status.Message2("some string")
);
```

This maps a constructed **Status** value to another constructed **Status**
value. It does NOT mean `m.put(1, 2)`, `m.put((true,"foo"), "some string")`,
nor a mapping between the two constructor descriptors.

`m.put(Status.Message1, Status.Message2)` MUST be rejected for payload-bearing
cases in a `HashMap<Status,Status>`: the expressions denote constructors or
case references, not complete `Status` instances. A future
`HashMap<EnumCase<Status>, EnumCase<Status>>` could intentionally map case
descriptors, but would be a *different* key/value type and API.

- Equality/hashing for constructed enums includes nominal enum identity,
  case identity/tag, and recursively equal payload content. Never hash only
  the wire tag and never equate two variants merely because their payload
  types (or payload values) match.
- A hash-key payload must have an immutable/frozen recursive `Hashable`
  contract. Mutating a tuple/list/record after insertion must not silently
  change its hash; either snapshot the key or statically reject mutable
  payloads. Borrowed views, capabilities, handles, actor refs and pointers
  are not implicitly valid keys or serialized payloads.
- `HashMap<Status,Status>` is not itself a serializable actor message until
  a canonical `Map<K,V>` codec is implemented for both backends. Do not reuse
  host Java/C++ hash maps or raw pointers across actor boundaries. For map
  encoding, canonical ordering, quotas, duplicate-key rejection and
  hash-independent wire identity need a separate cross-backend specification.

## Actor contract and wire identity

```ores
define pattern Messages as
    Status.Message1
    Status.Message2
    Status.Message3
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

The actor's declared closed message pattern is the source of allowed variants.
Every arm narrows by nominal case first. Exhaustiveness means every allowed
case is handled exactly once; duplicate or unreachable arms fail. This is the
**desired** end-to-end source behavior, not proof that all forms above execute
today. `Message` should be a receiver-context closed inbox type alias, not an
unbounded/dynamic value. Java currently has `ActorMail<Messages>` source
support; LLVM implements only a narrower typed payload subset and still
requires full general guest match/tuple/string lowering.

The existing canonical MessagePack v2 envelope includes a version,
compiler-bound closed-domain/case schema ID, shape SHA-256, enum name, integer
case tag and recursively encoded payload. It is **not** a serialized Java
enum object, a native address, a naked int, or a raw payload. The receiving
actor checks its own declared contract and decodes on its owned execution
turn; no cross-actor shared writable aliases. A shape digest is NOT an
authentication MAC. Preserve golden vectors across backend changes.

## Audit/verification obligations

1. Use the same positive/negative source corpus for implicit vs explicit
   tags, distinct variants with identical payload shape, string labels,
   destructuring, nonexhaustive/duplicate arms and invalid payloads.
2. Revalidate tag uniqueness/order, case and pattern limits at all compiler
   admission boundaries, not just the token parser.
3. Check hash/equality/freeze laws: reflexivity, symmetry, transitivity,
   `a == b => hash(a) == hash(b)`, no mutation of admitted map keys.
4. Property/fuzz-test encode/decode preservation of nominal identity and
   payload, reject unknown tags/shapes/versions and enforce byte/shape quotas.
5. Execute the *same* Oreslang actor source on Java/GraalVM and LLVM, with
   compiled tuple/string cases and real `self.respond` / `self.done`,
   including cancellation and isolation tests, before claiming parity.

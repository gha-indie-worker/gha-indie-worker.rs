# Nested nominal enum MessagePack v2 — executable subset contract

**Status (2026-10-11):** Java/GraalVM work in progress; LLVM native
guest dispatch still supports only nullary/int variants and MUST reject
nested enum actor mail until compiled source lowering and scheduler
semantics are implemented. This document is a byte-level contract and
a future compiler conformance input, not a claim of LLVM guest support.

## Language value
```ores
define enum Bar as
    Message1 = 1
end
define enum Foo as
    Message1: Bar
end
define match pattern Messages as
    ...Foo
end
```

`Foo.Message1(Bar.Message1())` is a constructed Foo with an owned
constructed Bar payload. Neither `Foo.Message1(1)` nor `Bar.Message1()`
alone has the same type.

## Wire representation (nonrecursive)
The existing canonical MessagePack v2 envelope remains:

`[2, schemaId, shapeSha256(utf8("oreslang.actor.shape.v2/" + shape)), payload]`

Top-level case `Foo.Message1` retains existing case ID derivation:
`schemaId = "oreslang.enum." + SHA256HEX("oreslang.tagged.domain.v1/Foo/12:Foo.Message1;") + "." + SHA256HEX("Foo.Message1#1")`.

Nested values use **the same** canonical tagged-array representation as
top-level payload values, without an independent inner envelope:
- `[enumOwner, numericTag]` for a nullary inner case.
- `[enumOwner, numericTag, payload]` for an inner case with payload.
- `enumOwner`, `numericTag` and the nested payload must agree with
  the compiler's exact closed enum declaration. No constructor is read
  from untrusted bytes or inferred from its payload shape.
- No implicit object identity, unboxed recursive schemas or Box transport.

The primitive/sequence descriptor grammar stays unchanged. New closed
nominal shape grammar (syntactic ASCII; only compiler-declared names):

`E(<owner length>:<owner>)[<numeric tag>:<case length>:<case name>=<case shape or N>;...]`

Nominal identifiers in a nested v2 closed actor domain (domain name,
outer enum owner and variant, and every nested enum owner and variant)
are **ASCII identifiers**, matching `[A-Za-z_][A-Za-z0-9_]*`,
with at most 256 bytes each. Names outside this grammar may remain
valid *local* identifiers but cannot enter this wire ABI. Reject rather
than silently replacing non-ASCII characters when hashing a shape.
The canonical expanded ASCII shape descriptor has a separate
**1,048,576-byte limit**; check it at compiler admission as well as
at encoding/decoding. The rule applies even if the selected case is small,
because the closed nested enum shape includes every declared case.

The case order is the declaration's strictly monotone tag order; `N`
means nullary. For Foo.Message1, the complete outer tuple shape is
`T[S,I,E(3:Bar)[1:8:Message1=N;]]`.

Reference Python MessagePack construction:
```python
sha = lambda s: hashlib.sha256(s.encode("ascii")).digest()
schema = "oreslang.enum." + sha("oreslang.tagged.domain.v1/Foo/12:Foo.Message1;").hex() + "." + sha("Foo.Message1#1").hex()
descriptor = "oreslang.actor.shape.v2/T[S,I,E(3:Bar)[1:8:Message1=N;]]"
wire = msgpack.packb([2, schema, sha(descriptor), ["Foo", 1, ["Bar", 1]]], use_bin_type=True)
```

Full golden hex (193 bytes):
```
9402d98f6f7265736c616e672e656e756d2e306162326236366364343839373130373332323563653264353938323735336137343830326636653261316437393861306632646364386665383233643232612e32643435643038313464653532363537303932626162663636333132343763376162313630316464303537393564643261653432383365303665383036373039c42071605bdfa3efdbcb5fe7aa4d4230f049269d4cd630b72c8c27f452c9b09ea42093a3466f6f0192a342617201
```

## Safety invariants
1. Decode only a compiler-bound schema from a trusted closed domain.
2. Compare full SHA-256 shape digest; accept canonical MessagePack only;
   reject unknown owner/tag, mismatched nullary arity and trailing bytes.
3. Validate nominal types at each nested level. Do not compare merely tags.
4. Immutable byte snapshot before mailbox admission; no cross-actor
   mutable host pointers. Quota/capability walks must inspect the complete
   decoded nominal graph, not only one outer payload.
5. Enforce max bytes (1 MiB), descriptor/value depth (64), node budget
   (100,000) and per-enum case count (4096) before expensive allocation.
6. Recursive schema graphs (even through List) and Box<T> remain
   unsupported on the wire. Acyclic finite nested values are in scope.
7. No claim that a digest is a MAC; this schema mechanism is type binding,
   not sender authentication.

## Release criteria
Both Java/GraalVM and LLVM must emit byte-identical vectors, reject
identical negative cases and execute Oreslang-sourced actors exchanging
nested messages. LLVM compiled nested guest code is explicitly pending.
Then extend to three actors with a typed upstream reply and
self.forward()/self.backward() under scheduler authorization, with
formal ownership and single-delivery checks.

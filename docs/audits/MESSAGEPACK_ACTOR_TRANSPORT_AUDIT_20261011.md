# Oreslang actor-message transport security audit — 2026-10-11

**Scope:** Java/Graal draft PR #623, LLVM draft PR #155, and their shared
`contracts/v1/design/messagepack_collection_vectors.json`. This records
executed component evidence and remaining production **release blockers**.
Neither draft certifies end-to-end actor-message wire transport.

## Invariants

- Source-level `ActorRef.send(value)` requires (a) a declared actor message
  domain and (b) a recursively proven Oreslang `Serializable` codec.
- Every actor uses the same observable serialization semantics whether SHARED,
  PRIVATE, UNTRUSTED, same-process, subprocess, or remote.
- Never transfer a raw host object, writable sender reference, closure, borrow,
  mutex, unsandboxed pointer or unversioned actor reference via a data message.
  Host/runtime CONTROL capabilities require a separate explicitly audited path.
- A message is snapshotted before mailbox admission; validation/encode failure
  cannot leave a partial mailbox entry or leak quota reservations.
- `rt copy` is a future optimization only for proven-equivalent complete
  snapshots; it must not substitute an alias to mutable state.

## Concrete findings

| ID | Finding | Mitigation in draft | Status |
| --- | --- | --- | --- |
| MP-01 | V1 `[1,schema_id,payload]` allows schema reuse with different tuple/list/array types | Wire v2 adds a canonical recursive shape SHA-256 as MessagePack `bin8(32)` and requires digest match | Fixed in standalone Java codec |
| MP-02 | Array length can trigger allocation before proving input budget | Require count <= remaining bytes and remaining global node quota before constructing list | Fixed in standalone Java codec |
| MP-03 | Nonminimal int/str/array representations make the same value have multiple accepted byte strings | Decoder rejects noncanonical MessagePack integer, UTF-8 string and array length tags | Fixed in standalone Java codec |
| MP-04 | Legacy wire frames might be confused with new frames | V1 explicitly rejected; v2 envelope is four fields | Fixed in standalone Java codec |
| MP-05 | Invalid UTF-8 or source UTF-16 surrogate can silently change bytes | Strict UTF-8 and UTF-16 codec error handling, no replacement characters | Fixed in standalone Java codec |
| MP-06 | Unknown host objects, deep/cyclic graphs, malformed lengths and huge strings | Fail-closed scalar/sequence encoder and bounded nodes/depth/bytes | Fixed for supported subset |
| MP-07 | Java source type resolver merges `Array<T>` and `List<T>` into `ListType(T)` | Keep the distinction in future compiler-owned schema IR; do not wire to real sends before this is solved | **OPEN, release blocker** |
| MP-08 | Java maps i8/i16/i32/u*/bigint/f32 to broad Primitive.INT/FLOAT, but codec supports only signed i64/finite f64 | Additional source mailbox declaration gate rejects incompatible width/signedness and Vector/Slice spellings | Partly contained; generic/import and IR preservation still **OPEN** |
| MP-09 | Native C++17 codec now executes byte-identical MessagePack v2 vectors, but LLVM compiler actor send/receive lowering is missing | Keep full native scheduler/compiler and source-level integration as release gates | **PARTIAL, release blocker** |
| MP-10 | Legacy host ActorRuntime transport still permits scoped capabilities, distinct from source Serializable messages | Opt-in trusted mailbox binding now binary-snapshots real actor sends; keep CONTROL capabilities separate and migrate source/API coverage | **PARTIAL, release blocker** |
| MP-11 | An attacker can submit a matching digest but must not choose the decoded type or serializer | Codec shape/schema must come from trusted compiler descriptor, not from incoming message | **OPEN, runtime integration gate** |
| MP-12 | Java source `@Serializable` could be interpreted as proof without generated encode/decode | Parser rejects until derivation exists | Fixed as fail-closed reservation |
| MP-13 | Same-file type aliases are forbidden inside actor lexical boundaries; that early rejection does not validate imported aliases | Assert the *actual* lexical diagnostic, add imported alias/end-to-end codec tests later | **OPEN imported-alias conformance gate** |
| MP-14 | Concurrent mutation of host collections during `encode` could violate snapshot-at-send even if returned bytes are immutable | Source ownership/exclusive handoff must prevent concurrent mutation or lock a stable snapshot, prior to runtime admission | **OPEN, runtime integration gate** |

## Wire v2 subset (Java reference)

```
MessagePack array(4) [
  integer(2),
  string(fq_schema_id),
  bin8(32, SHA256("oreslang.actor.shape.v2/" + shape)),
  schema_guided_payload
]
shape ::= B | I | F | S
        | T[shape,...] | FA[shape,...] | FL[shape,...]
        | A[shape] | L[shape]
```

Values are supported only for bool, signed 64-bit integers, finite f64,
valid UTF-8 strings and recursively typed tuples/fixed arrays/fixed lists/
dynamic arrays/lists. This does not cover `Option`, `Result`, arbitrary
records, enums, user classes, unsigned/big integers, complex/decimal, channels
or live actor capabilities. Schema major version is separate from wire major
version (a schema name ending in `.v1` can validly use wire v2).

The digest is a **shape commitment, not an authenticated signature or MAC**.
No untrusted caller may choose a codec from a deserialized type name or
dynamically execute host constructors. A future externally exposed runtime
requires authenticated sender identity, recipient/generation checks, quota
reservation and structured supervision/cancellation release.

## Evidence and non-evidence

- Shared v2 binary golden vectors are at
  `contracts/v1/design/messagepack_collection_vectors.json` with identical
  Java and LLVM Git blob SHA
  `9937eb2bcd11ad59d59f4a0e4ac83953e5e5a1e6`.
- The Java standalone codec source has Git blob
  `e8a486d9f8540ac1ab246a3095fe78b05817ba1a`, mirrored byte-for-byte
  into a funded GitHub Actions test repository without cross-org secrets.
- The funded runner executes exact-source Java parser, TypeChecker, and
  MessagePack round trips, attack regressions (shape confusion, tampered
  hash, downgrade, noncanonical numbers/string/array, short input, oversized
  array declarations) and the manifest identity checks.
- An executed funded component job is **not** a Maven/JUnit full-suite pass,
  GraalVM native-image test, LLVM/Clang/CTest run, or actual cross-actor
  scheduler delivery proof. Source org jobs that fail with zero executed
  steps are runner-allocation failures, not a compiler test result.

## Merge-admission requirements

1. Integrate immutable compiler-owned schema descriptors, preserving exact
   source collection kind, numeric width/signedness, enum variant/tag, type
   package identity and fully substituted imported/generic types.
2. Wire encode/snapshot to public source `ActorRef.send` before enqueue;
   receiver decode must validate type, quotas, generation and ownership,
   and release staged bytes on cancellation/actor shutdown.
3. Keep internal CONTROL-capability delivery separate and audited.
4. Implement LLVM/native byte-identical v2 schema hashing and MessagePack
   codecs, with cross-runtime positive/negative differential tests.
5. Add schema evolution/replay/unknown version/tag tests, fuzz malformed
   bytes, malicious host values, mutation races, actor startup and teardown.
6. Benchmark MessagePack versus `rt copy` under realistic load; permit only
   semantically equivalent fast paths.
7. Execute Java Maven/JUnit and Graal native-image, LLVM Clang/CTest and
   shared same-source programs at exact PR heads with funded runners.
8. Keep both PRs **DRAFT** until all production gates pass; do not mark
   design-only conformance fixtures as executed compiler tests.


## Executed integration milestone (2026-10-11, after initial audit)

**Java/GraalVM source and actor runtime:**
- The Java draft now contains \`dev.oreslang.types.ActorWireSchema\`, which generates
  source-spelling-aware canonical schemas, preserving Array vs List and exact
  Tuple/FixedArray/FixedList arity, and rejecting missing numeric codecs.
- \`ActorRuntime\` supports one-time trusted mailbox-schema binding and
  MessagePack encode/decode **before** regular queue admission; both shared
  and private actors were exercised through their real mailboxes, including
  sender list mutation, immutable decoded values, invalid-message recovery,
  and late-binding rejection. Existing legacy host CONTROL capabilities stay
  on their separate pre-existing path.
- The actual Java \`OresEvalRootNode\` source executor now derives a
  \`MailboxSchema\` from a local actor's \`receive(ActorMail<T>)\` signature at
  spawn time, bound to a code-unit and actor name identity.
- **Executed exact-project Maven test:** [GHA #38168958540]
  (https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38168958540)
  verified 76 exact Java source/test/resource blobs and **39 JUnit tests
  passed, zero failures/errors**, including real source-compiled tuple/list
  mailbox execution.

**Native C++17 binary codec parity:**
- The LLVM draft includes the standalone \`MessagePackWireV2.hpp\` encoder/
  decoder, a native golden-vector/hostile-input test and an optional OpenSSL
  Crypto-backed CTest target.
- **Executed native funded proof:** [GHA #38169364693]
  (https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38169364693)
  compiled the exact C++17 sources and matched all six shared Java/Python
  v2 hex vectors byte-for-byte, plus malformed/schema-confusion cases.
- These passing tests prove *binary codec subset parity*, not LLVM source
  compiler actor send/receive lowering or supervisor mailbox integration.
  A full LLVM CMake/CTest suite and Java native-image execution remain open.

**Production blockers remain:** Source classes/generic imported aliases,
schema evolution, authenticated remote actors, structured codec registration
for all numeric widths and tagged alternatives; native LLVM actor lowering;
atomic admission/cancellation with proven byte-quota accounting at all
bindings; concurrent-mutation races; native image and complete compiler
conformance; independent approval. The PRs remain **DRAFT**.


## Binary mailbox frame upgrade (executed follow-up)

The Java \`ActorRuntime.send\` path has been upgraded again: schema-bound
mailboxes **physically enqueue the runtime-owned MessagePack v2 byte[] frame**,
not a pre-decoded List/Tuple. \`ActorCell\` decodes that frame only after the
recipient acquires its exclusive mailbox turn, before passing the decoded
recipient-owned value to host \`Behavior\` or source \`ActorCodeExecutor\`.
The sender pre-decodes solely for strict admission and memory estimation,
then reserves space conservatively for **both retained wire bytes and the
estimated decoded graph**. The ordinary mailbox envelope \`release\` path
releases that reservation on dispatch, error or cancelled queued mail.
Host actors without a bound schema and internal CONTROL/capability traffic
continue on their separate pre-existing path.

**Latest executed exact-source Maven proof:**
[funded workflow #38169821931](https://github.com/gha-indie-worker/gha-indie-worker-docs/actions/runs/38169821931),
Java code pinned to commit \`e4a110b6defa82a484ed82f48d2377b187fabb8c\`.
76 source/test/resource SHA verifications passed; six targeted JUnit classes
**39 tests passed, zero failures/errors, BUILD SUCCESS**. This supersedes
the earlier 39-test proof that only snapshotted/deep-decoded before enqueue.

**Not yet established:** persistent decoded-region accounting across an
asynchronously suspended source receive; complete cancellation and queue
capacity stress testing; LLVM source actor mailbox connection; exact-head
native image and full native CMake/CTest; remote sender authentication and
full user-defined/generic serialization. Both PRs stay DRAFT.

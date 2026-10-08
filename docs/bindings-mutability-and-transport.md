# Binding, referent mutability and transport conformance

Oreslang's proposed canonical contract, agreed 2026-10-06. The compiler and ownership checks are authoritative only after validation; unsafe or unproved paths must fail closed.

| Declaration | Reassign name | Mutate referent |
| --- | --- | --- |
| `const x` | no | no |
| `val x` = `const mut x` | no | yes if ownership permits |
| `let x` | yes | no |
| `let mut x` | yes | yes if ownership permits |

In select cases, `case job = readch(jobs) -> { ... }` defaults to immutable/read-only. `case val job` fixes binding but permits referent mutation; `case mut job` is shorthand for `case let mut job`. Only the winning case receives ownership. Existing `case readch channel: val job { ... }` may be accepted for compatibility.

`mut` is not a bypass around borrowing: a live immutable borrow excludes mutation; only one mutable borrow may exist at once; a moved owner is unusable; nested fields, indexes, collection methods, closure captures, deferred continuations, and actor access paths must be checked transitively. Mutation through a readonly view is rejected, even if another alias may have write permission at other times. Use canonical `rt borrow`, `rt copy`, `rt take`, `rt share`, never raw pointer operators as public syntax.

Ordinary class receivers are borrowed views, so an ordinary method cannot keep `self` live across `await`. Return a `Future<T>` from the method (for example, with `nb readch self.input`) and await it in the owning caller after the method returns. Imported and polymorphic methods obey this same restriction; a receiver named like a builtin namespace does not bypass it.


**Transport:** actor mailbox messages are serialized/frozen according to boundary policy. They cannot contain executable functions, raw object pointers, live borrows, channels/select handles, or shared mutable graphs. Ordinary execution-domain-local `Channel<T>` values may transport owned functions/closures and owned object references; writes move move-only payloads and channel types reject borrowed payloads transitively. A local channel itself cannot cross an actor boundary, so this does not bypass mailbox isolation.

**Closed structural values:** `infer struct{foo: "bar"}` infers an exact closed shape and is permanently readonly. It must be bound through a non-mutable referent view (for example `const value = ...` or `let value = ...`); `val`, `const mut`, `let mut`, mutable borrows, field assignment, added fields, and mutable aliases are rejected. Readonly capability is carried by the structural value/type and therefore cannot be erased by a type annotation, alias, conditional/common-type join, or nested projection.

An explicitly-shaped anonymous struct uses `struct{foo: string, bar: bool}{foo: "bar"}`. Its declared shape exists independently of the initializer. A mutable binding may leave declared fields uninitialized and assign those declared slots later, for example `const mut value = struct{foo: string, bar: bool}{foo: "bar"}; value.bar = true;`. Undeclared fields are never addable. A read-only binding must initialize every declared field immediately. A partially initialized explicit struct cannot be read as a whole, aliased, passed, returned, or destructured until all declared fields are initialized; reading an uninitialized field fails closed. The runtime mirrors these checks with immutable inferred-record storage and explicit uninitialized-slot sentinels.

Test matrix: all four declaration modes; const initialization with runtime expressions; field assignments and mutating methods at multiple depths; aliasing, moves, borrow lifetimes and await; select-case binding/losing cases; local channel functions and captures; recursively rejecting functions/pointers through serialized mailboxes.


The same declaration matrix applies to destructuring and iterator bindings. Examples: `val [x] = value` gives a fixed binding with mutable referent access; `let [x]` permits rebinding but not referent mutation; `let mut [x]` permits both. `for val x of values` and `for let mut x of values` follow identical rules.

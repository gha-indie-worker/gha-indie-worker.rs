# Oreslang language design (v0.6)

Oreslang is a statically typed guest language for GraalVM/Truffle. Named types are nominal by default; structural compatibility is explicit at selected boundaries. Its core invariants are explicit mutation, actor-owned mutable heaps, message-only actor communication, read-only sharing, and a stricter isolate profile for untrusted FaaS execution.

## Files, modules, and imports

A source file may contain multiple named modules. A module is a namespace: exported members are accessed through the module name, such as `math.add(1, 2)`.

```ores
define module math
  pub fnc add(int a, int b) => int {
    return a + b;
  }
end

define module app
  pub fnc main() => void {
    val answer = math.add(40, 2);
    stdio.println(answer);
    return;
  }
end
```

Imports are explicit about what kind of symbol is entering the compilation unit:

```ores
import module foo from "../xyz";
import module foo as apiFoo from "../xyz";
import module {foo, bar} from "../xyz";
import class Widget as ApiWidget from "../xyz";
import fnc add as apiAdd from "../xyz";
import fnc * as funcs from "../xyz";
import * as package from "./xyz";
```

Wildcard imports always require a namespace alias. A single named module/class/function import may use `as` to choose its local binding; the original source name still controls export resolution. This avoids namespace pollution while supporting Kotlin-style disambiguation. Import paths are part of the AST/compiler contract; filesystem/package resolution is a host build/bundling concern so strict isolates do not gain ambient filesystem access merely by using `import`.

Java host classes use an explicit `java:` URI and the same alias syntax:

```ores
import class ArrayList as JArrayList from "java:java.util.ArrayList";
```

The selected name (`ArrayList`) must match the Java simple class name; `JArrayList` is only the Oreslang-local alias. Java imports never grant authority by themselves: runtime use additionally requires the `JAVA_INTEROP` capability and an exact host-class allowlist supplied by the launcher/embedder.

### Circular imports and file initialization

Import cycles are legal. Oreslang does not reject a program merely because its
file/module graph contains a cycle such as `a.ores -> b.ores -> a.ores`.

The loader uses a staged lifecycle:

1. parse and statically validate the complete reachable source graph;
2. resolve/link imports for every code unit;
3. compute strongly connected components (SCCs) of the import graph;
4. for each dependency-first SCC, verify that **all** members are linked;
5. run each member's optional file init hook;
6. after initialization, invoke the entry unit's `main`.

A file init hook has the exact shape:

```ores
fnc init() => void {
  // side effects are allowed here
  return;
}
```

It is private, synchronous, non-actor, non-generic, takes no parameters, and
returns `void`. The hook runs at most once for that loaded code-unit
generation. Inside a cycle, init hooks execute in deterministic normalized
code-unit-id order, but code must rely only on the stronger barrier guarantee:
**every peer in the cycle is already linked before any peer's init begins**.

This means an init hook may call exported declarations from a cyclic peer
without observing an "unloaded module" state. If application state requires a
specific sequencing relationship *between* two init hooks in the same cycle,
that relationship should be made explicit in application code rather than
inferred from the import edges.

## Module interfaces / OCaml-style module signatures

Interfaces can describe the structural public shape required of a module. A module opts into checking with `@AdheresTo(...)`:

```ores
define module contracts
  define interface MathApi
    fnc add(int a, int b) => int;
    String name;
  end
end

@AdheresTo(contracts.MathApi)
define module math
  pub fnc add(int a, int b) => int { return a + b; }
  pub val String name = "math";
end
```

Only exported (`pub`) module members satisfy an adherence contract. `@AdheresTo(A, B)` may name more than one interface.

## Functions and returns

Functions use `fnc` and are private by default. `pub` exports them. Return statements are always explicit; a non-`void` function must return on every control-flow path.

```ores
fnc add(int a, int b) => int {
  return a + b;
}

@Ret<int>
fnc answer() {
  return 42;
}
```

`@Ret<T>` and `=> T` are equivalent. If both are present they must agree. A function returns exactly one value; multiple logical values are represented by a tuple, array, object, class value, or another aggregate.

Return types may be unions, homogeneous arrays, finite tuple types, or structural record types:

```ores
type intOrBoolOrString = bool | int | string;

fnc mixed() => Array<type intOrBoolOrString> {
  return [3, true, "yes"];
}

fnc fixed() => [int, bool, string] {
  return [3, true, "yes"];
}

fnc named() => {foo: int, bar: string} {
  return obj{foo: 5, bar: "x"};
}
```

The `type` marker inside `Array<type intOrBoolOrString>` is accepted as an explicit alias marker; `Array<intOrBoolOrString>` is equivalent. A finite tuple type records exact arity and the type of each position even though the interpreter represents the value with a JVM `List`. A record type names required members; extra members remain compatible with the structural type system.

## Bindings

Every local binding is declared as exactly one of:

- `const`: compile-time constant; cannot be reassigned.
- `val`: runtime single-assignment binding; cannot be reassigned.
- `let`: mutable binding and the only local binding kind that may be reassigned.

```ores
const max = 10;
val request_id = process.context_id;
let retries = 0;
retries = retries + 1;
```

Destructuring carries mutability per element. A binding kind propagates through later unqualified names until another explicit binding kind appears. A binding kind may also prefix the whole pattern.

```ores
[const number, flag, answer] = fixed();   // all const
const [x, y, z] = fixed();               // all const
[const head, let middle, tail] = fixed(); // head const; middle/tail let

const {foo, bar} = named();
// Equivalent per-item spelling, shown in a separate scope:
// {const foo, const bar} = named();
```

A bare `_` is a sequence discard pattern: it consumes that array/tuple position without declaring a variable, so it can be repeated in the same sequence pattern or reused by later destructures. Object patterns do not accept bare `_` because object destructuring is key-based rather than positional.

```ores
[const code, _, let body] = (200, "ignored", "ok");
[const next, _, let tail] = (201, "ignored again", "done");
[_, _, const final] = (1, 2, 3);
```

`_` is not readable after the destructure because no lexical binding is created for it. A destructured `const` is an immutable runtime binding; unlike a standalone `const x = ...` declaration, the aggregate being destructured does not need to be a compile-time constant.

Sequence destructuring requires a returned tuple or array/list. Finite tuples are checked for exact arity and per-position type. Object destructuring requires a record/map-like value and every requested key must exist. If the returned type is a union, destructuring is allowed only when every union alternative supports the requested pattern; each extracted binding receives the union of the corresponding alternative member types. Function-parameter destructuring is intentionally not part of this syntax yet.

## Classes, receivers, multiple inheritance, and interfaces

Class headers use `as` as the required body delimiter. The canonical form is `define class Name as ... end`; when `extends` or `implements` are present, `as` follows the complete class header.

Methods omit `fnc`. Instance methods always have an implicit receiver named `self`.

```ores
define class Box<T> as
  val T value;

  @Ret<self>
  identity() {
    return self;
  }
end
```

The explicit receiver form remains available:

```ores
find(self Box)(int key) => self {
  return self;
}
```

The receiver variable name is always `self`.

A class may list multiple parent classes and multiple interfaces:

```ores
define class Combined extends Cacheable, Serializable implements HasId, Named as
end
```

Parent order is significant and is the deterministic v0.2 method-resolution order after child methods: the first declared parent is searched before the next parent. The static checker rejects inheritance cycles and incompatible inherited member shapes. Child members may override inherited members only with compatible types.

`Object` and `List` are extensible base classes:

```ores
define class RecordBag extends Object as
end

define class Names extends List as
end
```

Inline object and array literals are values, not classes, and cannot be inherited from.

## Inline values

Structural inline object:

```ores
val user = obj{name: "Ada", age: 37};
stdio.println(user.name);
```

Static object/map keys may be identifiers, reserved member keys such as
`stop`/`do`/`done`, or strings written with either single or double
quotes. Backticks make the key dynamic: the expression between the backticks
must evaluate to a string.

```ores
val key = "score";
val stats = obj{
  stop: 1,
  'do': 2,
  "done": 3,
  `key`: 4
};
```

An `obj{...}` containing a dynamic key has type `DynamicStruct<T>`, where
`T` is the joined value type. A `DynamicStruct<T>` can also be created
directly with `new DynamicStruct<T>()`; it accepts arbitrary string keys but
only values assignable to `T`.

Inline array:

```ores
val values = arr[10, 20, 30];
val first = values[0];
```

`arr[...]` is the canonical inline-array spelling. The original bare `[...]` literal remains accepted for source compatibility and destructuring migration.

Tuples preserve per-position static types. Parenthesized tuple literals and list-backed values returned against a finite tuple type both retain the declared positional types:

```ores
val pair = (1, "one");
[const number, let label] = pair;

fnc result() => [int, bool, string] {
  return [3, true, "yes"];
}

const [num, ok, answer] = result();
```

## Structural typing and interfaces

Interfaces are structural contracts. Explicit `implements` asks the compiler to prove conformance and documents intent; structural compatibility does not require nominal ancestry in every context.

```ores
define interface Named
  String name;
end

define class User implements Named as
  pub val String name;
end
```

Class interface satisfaction uses public members, including inherited public members.

## Option, Result, and null

Oreslang does **not** have ambient nullable references. A bare `null` value is a compile-time error, and `null` is not a standalone variable/parameter/return type.

Optionality is explicit, using Rust-style `Some(value)` and `None`:

```ores
fnc lookup(bool found) => Option<int> {
  if found; do
    return Some(42);
  else
    return None;
  fi
}
```

Fallible operations use `Result<T, E>`, constructed with `Ok(value)` or `Err(error)`. `Result` always has exactly two explicit type arguments.

```ores
val Option<int> present = Some(42);
val number = present.unwrap();

val Option<int> missing = None;
val safe = missing.unwrap_safe();          // Err(OptionUnwrapError(...))

val Result<int, String> parsed = Ok(123);
val same = parsed.unwrap_safe();            // Ok(123)
```

- `Option<T>.unwrap() -> T` returns the `Some` payload and panics on `None`.
- `Option<T>.unwrap_safe() -> Result<T, OptionUnwrapError>` never panics for absence.
- `Result<T,E>.unwrap() -> T` returns the `Ok` payload and panics on `Err`.
- `Result<T,E>.unwrap_safe() -> Result<T,E>` never panics; it preserves the error-as-value carrier.
- `expect(String)` is the descriptive panicking form; `unwrap_or(T)` supplies a fallback.
- `is_some()/is_none()` and `is_ok()/is_err()` inspect variants without extraction.

Like Rust methods that take `self`, extraction consumes a move-only `Option` or `Result`. `Option<T>` is `Copy` exactly when `T` is `Copy`; `Result<T,E>` is `Copy` exactly when both payload types are `Copy`.

Owned sum values cannot hide lexical borrows until explicit lifetime parameters exist, so `Some(&value)`, `Ok(&value)`, and `Err(&value)` are rejected.

Panics are distinct from ordinary recoverable errors. Normal `try/catch` does not swallow an unwrap panic, while lexical cleanup and `finally` still execute during unwind. Use `unwrap_safe()`, matching, or explicit variant inspection when absence/failure should remain data.

`Option<null>` is accepted only as an explicit type-level escape hatch when an interoperability boundary truly needs to preserve a null marker. The `null` marker cannot escape that direct `Option<null>` position. `Option<void>` is invalid; use `void` when a function returns no value.

## Numbers

Built-in numeric families include integral, floating, decimal, and complex types. Imaginary literals use `i`:

```ores
const complex z = 3 + 4i;
```

Numeric widening is loss-aware; real values can widen toward complex values, but silent lossy narrowing is not performed.

## Lambdas

Lambdas are lexical closures by default and use `->`. The canonical block
form keeps returns explicit:

```ores
val Fnc<int, int> inc = |int x| -> {
  return x + 1;
};
```

A normal lambda may capture activation-local bindings from its enclosing
function or block. Captured mutable state remains part of the closure.

## Non-lexical callables (`nlex`)

`nlex` is an opt-in **capture barrier**, not a ban on global/module lookup.
It prevents a callable from capturing bindings owned by an enclosing runtime
activation, so an `nlex` lambda does not retain or snapshot an outer local
environment.

Inside an `nlex` region:

- parameters and locals declared inside the callable remain available;
- locals shadow module/global/import bindings normally;
- module members, imports, top-level callables/classes, and built-ins remain
  statically resolvable;
- enclosing activation-local bindings cannot be captured;
- lambdas nested in an `nlex fnc`, `nlex routine`, or `nlex` lambda inherit
  the barrier.

Actor entry points remain governed by their actor isolation rules. `nlex` may
add a capture-free guarantee to an actor fnc, but it does not replace mailbox,
private-slice, or shared-actor isolation.

## Conditionals

`fi` is a real, distinct conditional terminator. It is not an alias for module/class `end`.

```ores
if ready, authorized | process.is_admin; do
  return serve();
elseif retryable; do
  return retry();
else
  return reject();
fi
```

Within a condition, comma means AND and `|` means OR. Comma binds more tightly.

## Exceptions and defer

Both structured exceptions and lexical `defer` are supported:

```ores
try {
  risky();
} catch (err) {
  recover(err);
} finally {
  cleanup();
}
```

`defer` executes in LIFO order when its lexical scope unwinds, including returns and exceptional exits.

## Callable-only reserved keywords

`stop`, `do`, and `done` are reserved language keywords. They cannot be used as ordinary identifiers for bindings, parameters, fields, types, classes, modules, or bare function references.

They have one narrow compatibility exception: the three words may be declared as callable names and used when invoking that callable. This includes `fnc`/`routine` declarations, class/actor methods, interface function signatures, `import fnc` selections, direct calls such as `stop()`, and qualified calls such as `worker.done()`.

The exception does not turn the keywords back into general identifiers. For example, `val stop = 1`, `fnc f(int do)`, `val callback = done`, and `val callback = worker.stop` are invalid.

## Async / await

`async` and `await` are reserved and parsed. `await` unwraps future-like runtime values. The scheduler is intentionally separate from the language surface so actor isolation does not depend on a specific OS-thread implementation.

## Actors

Oreslang uses an Akka-style dispatcher model: an actor is **not** a thread. Every actor owns one mailbox, and at most one mailbox turn for a given actor may execute at a time. Actors are multiplexed over bounded thread pools, so the carrier thread may change between turns.

There are two actor execution domains:

```ores
pub actor fnc worker(int value) => int {
  return value;
}

shared actor Account {
  let int balance = 100;

  pub fnc withdraw(int amount) => void {
    self.balance = self.balance - amount;
    return;
  }
}
```

- an unqualified `actor` is **private**;
- `shared actor` is a **shared-memory-capable** actor;
- private and shared actors are scheduled on **different dispatcher pools** for bulkheading;
- compiler-generated/context-aware actor factories are capture-free for **both** actor kinds; mutable host state must enter through messages or explicit runtime-owned capabilities rather than Java closure capture;
- trusted host embedding has separately named supervisor-only construction escape hatches, and adversarial policies reject them;
- both kinds still process their own mailbox serially;
- actor-owned `let` fields may mutate during a mailbox turn because that turn is the exclusive mutation capability for `self`;
- no lock is required around ordinary actor-owned fields, including fields of a shared actor;
- actor `self` and move-only state rooted at `self` cannot escape the mailbox turn by value or returned borrow; copy-like values such as integers, booleans, and strings may be returned normally;
- synchronized shared memory requires the host-granted `SHARED_MEMORY` capability.

Private actors do not accept explicitly shared mutable memory. Each private actor owns a **confined memory slice** identified by its actor id, independent of whichever dispatcher thread happens to execute a mailbox turn. Incoming messages are isolation-copied into that actor domain and charged against the destination slice before mailbox admission. Compiler-managed actor state allocations use the same slice.

The slice has two simultaneous limits:

- a per-actor limit from that actor's `IsolatePolicy.maxHeapBytes()`;
- an aggregate private-actor memory budget from the parent runtime policy.

This prevents many private actors from multiplying the parent's memory ceiling. Destroying the actor closes its slice and releases its accounting.

The JVM backend's slice is a language/runtime ownership and accounting boundary, not a separate Java GC heap. The slice follows the actor id across dispatcher workers; it is not thread-local state. When physical heap separation is required for adversarial tenant code, the same private-actor semantics must be backed by a cross-thread-capable private region or a separate Graal polyglot/native isolate.

Shared actors may additionally receive:

1. deeply immutable `Shared<T>` values; and
2. explicit synchronized shared cells.

The runtime primitive for the second case is `SyncCell<T>`. A cell stores only frozen state and serializes replacement updates under a lock. Shared-cell state is quota-accounted against the same parent actor-memory ceiling as private actor slices. Private actor turns cannot create, inspect, mutate, or close a `SyncCell<T>`. This is the intended lowering target for a future `sync` language construct; `sync` is **not** an implicit lock around actor methods.

Actor message graphs are cyclicity-checked and bounded by nesting depth, node count, and logical byte quotas before admission so malicious container graphs cannot turn actor transport into unbounded recursion, CPU, or memory use.

This preserves the central invariant:

> Actor state is mutated through mailbox ownership. Shared mutable state outside an actor is exceptional and must use an explicit synchronization abstraction.

Arbitrary mutable host objects remain invalid actor messages. Actor kind is part of the public ABI, so changing a normal callable/class into a private or shared actor invalidates dependent compiled units.

Shared writable handles use transactional publication. A `SharedMutex<T>` is reserved to the destination runtime before mailbox visibility, committed only after queue admission, and unbound again when first publication fails. This prevents failed sends from accidentally claiming a writable capability for the wrong runtime.

## Isolates

An isolate is stricter than an actor and is intended as a FaaS/tenant security boundary. Strict isolate contexts deny host reflection, native access, arbitrary filesystem/IO, child-process creation, guest-created threads, environment access, and unrestricted polyglot access unless an explicit capability is granted by the host.

Actors may run inside an isolate. Actor semantics never weaken isolate policy.

## Built-in globals

`process` is an Oreslang runtime descriptor/capability facade, not unrestricted OS process access. `stdio` is capability-scoped standard IO. `print(value)` is shorthand for the output facade.

## Compiler pipeline

1. UTF-8 source -> lexer.
2. lexer -> parser / AST.
3. imports and declarations are collected without executing user code.
4. generic, structural, module-interface, inheritance, mutability, and return-flow checks run.
5. actor/isolate sendability constraints are enforced at relevant runtime boundaries.
6. checked source is lowered/executed as Truffle guest code.

The parser and static checker execute no user code.


## File-level entrypoints

Named modules remain the normal namespace unit, but a source file may also contain file-level callables such as an entrypoint. The compiler places those declarations in an internal file-root namespace; that namespace is not written by user code.

```ores
define module x
  define class y as
  end
end

pub routine main() => void {
  val y = new x.y();
  stdio.stdout.write(y)
}
```

Qualified names such as `x.y` retain their module namespace.

## `fnc` versus `routine`

`fnc` is the recursive/function form. It may participate in recursive call graphs. Tail-position calls from `fnc` are optimization-eligible, but v0.3 deliberately does **not** promise that every recursive `fnc` executes in constant stack space yet.

`routine` is the non-recursive procedural form:

```ores
pub routine main() => void {
  run_app();
}
```

The static checker rejects direct or indirect call cycles that contain a routine. Routines are not tail-call-optimization targets. This makes entrypoints, orchestration steps, and lifecycle procedures explicit.

Lambdas may recurse when their binding supplies an explicit function type so the closure's own signature is available while its body is checked:

```ores
let Fnc<int, int> fact = |int n| -> {
  return n == 0 ? 1 : n * fact(n - 1);
};
```

## Semicolons

Semicolons are strongly recommended. They remain the canonical formatter output.

They may be omitted only where the parser has an unambiguous structural boundary, such as the final expression immediately before `}`, `fi`, or `end`. Oreslang does not use broad JavaScript-style automatic semicolon insertion.

```ores
pub routine main() => void {
  stdio.stdout.write("done")
}
```

## Nominal typing and opt-in structural parameters

Named classes and interfaces are nominal by default. Structural matching at an API boundary is explicit with `@Structural`:

```ores
pub interface Brand {
  markerBrand: 'marking/branding'
}

fnc consume(@Structural Brand value) => String {
  return value.markerBrand;
}
```

A value does not need to nominally implement `Brand` for that parameter, but its public/static shape must satisfy the interface. Without `@Structural`, the normal nominal implementation/inheritance rules apply.

Interfaces may inherit from other interfaces and support literal-string marker fields:

```ores
pub interface Bar {
  markerBrand: 'marking/branding'
}

pub interface Foo extends Bar {
}
```

Explicit `implements` and module `@AdheresTo(...)` checks remain structural conformance proofs.

## Method overloads

Only methods overload, and only by arity:

```ores
define class Lookup as
  find() => Option<int> {
    return None;
  }

  find(int id) => Option<int> {
    return Some(id);
  }
end
```

Two methods with the same name and same arity are a compile-time error even when their parameter types differ. Top-level/module `fnc` and `routine` declarations never overload.

## Ternary expressions

The ternary operator is right-associative and lazy in its selected branch:

```ores
fnc find(bool found) => Option<int> {
  return found ? Some(42) : None;
}
```

## Loops, iterators, and scheduler safepoints

Oreslang supports conventional imperative loops:

```ores
for (let i = 0; i < 10; i = i + 1) {
  work(i);
}
```

and iterator-style loops:

```ores
for (val item of values) {
  work(item);
}
```

Classes can expose a JavaScript-like iterator symbol:

```ores
define class Bag as
  [Symbol.iterator]() => Array<int> {
    return arr[1, 2, 3];
  }
end
```

The compiler/runtime inserts a scheduler safepoint on **every loop iteration**. The current runtime hook checks cancellation/interruption and yields execution; it is intentionally centralized so actor supervisor/control-mailbox polling can evolve without changing source syntax. User code does not receive ambient thread-control capability.

This means Oreslang does not require recursion as the only way to loop, while still giving actor/isolate schedulers a compulsory cooperation point inside generated loop execution.

## Standard output

In addition to `stdio.print` and `stdio.println`, the stream-shaped form is available:

```ores
stdio.stdout.write(value);
stdio.stdout.println(value);
```


## Execution profiles: JIT, AOT, and hybrid

The same Oreslang source model supports three deployment profiles:

- **JIT** — normal GraalVM/JVM host with Truffle JIT available.
- **AOT** — Native Image host with the Truffle interpreter retained and guest JIT disabled. This is the conservative mobile/FaaS profile and still supports source hot reload because new Oreslang source is data consumed by the precompiled interpreter.
- **HYBRID** — Native Image host plus Truffle guest JIT on targets where executable-code generation is permitted.

The CLI accepts `--mode=jit|aot|hybrid` and `--platform=server|windows|macos|linux|android|ios`. The iOS execution contract is intentionally AOT-only. Source hot reload does not depend on executable dynamic libraries, JNI, or NFI.

Maven profiles:
- `mvn -Pnative-aot -DskipTests package`
- `mvn -Pnative-hybrid -DskipTests package`

## Capability-secure isolates

Security is layered. Oreslang uses a deny-by-default language capability policy **in addition to** Graal/Native Image isolation and the host OS/mobile sandbox.

An isolate policy can independently allow or deny:

`STDIN`, `STDOUT`, `PROCESS_INFO`, `ACTOR_SHARE_READONLY`, `SHARED_MEMORY`, `NETWORK`, `FILESYSTEM_READ`, `FILESYSTEM_WRITE`, `ENVIRONMENT`, `HOT_CODE_LOAD`, `FFI`, `NATIVE`, `REFLECTION`, `CHILD_PROCESS`, `THREAD_CREATE`, and `POLYGLOT`.

The trusted compiler API can reject forbidden API usage before execution:

```java
OresCompiler.validateForIsolate(source, policy);
```

Runtime facades perform the same check again. A source file therefore cannot grant itself a capability. The launcher/supervisor chooses policy.

The strict FaaS baseline permits only stdout. Host reflection, native access, unrestricted polyglot access, environment access, guest-created threads, and host IO remain disabled at the Graal context boundary.

Actor cells may receive a policy stricter than their parent runtime. Their mailbox capacity is also bounded by that policy.

## Hot reload without FFI

`HotReloadManager` loads each code revision into a new versioned Polyglot context/generation:

1. source arrives as data;
2. syntax/type/capability checks run;
3. a fresh restricted guest context is created;
4. the validated generation is staged and atomically becomes active without executing guest code;
5. the supervisor explicitly starts the generation when its actor/request boundary is ready;
6. the previous generation may remain alive while requests/actors drain;
7. the supervisor explicitly retires it.

Each generation receives a monotonically increasing id and SHA-256 source digest.

This model does not require `dlopen`, `LoadLibrary`, JNI, or Truffle NFI. A production server may additionally map each context to a Graal polyglot/native isolate. On AOT-only targets the precompiled interpreter executes newly loaded Oreslang source; on JIT-capable targets the same source may warm into optimized machine code.

## Explicit structural calls

Structural compatibility is never silently enabled for a nominal parameter. These three spellings are equivalent:

```ores
pub interface Bar {
  marker: 'brand'
}

pub interface Foo extends Bar {
  markerBrand: 'marking/branding'
}

fnc a(@Structural Foo y) => void {
  return;
}

fnc b(y structural Foo) => void {
  return;
}

@AllowStructural(y)
fnc c(y Foo) => void {
  return;
}
```

All three may accept:

```ores
val branded = obj{
  marker: "brand",
  markerBrand: "marking/branding"
};

a(branded);
b(branded);
c(branded);
```

Without one of those explicit structural opt-ins, passing that object to a nominal `Foo` parameter is a compile-time error.

`structural` is a contextual keyword, so existing identifiers named `structural` remain legal elsewhere.

## Receiver identity and method values

`self` is injected by the compiler/runtime as an immutable receiver binding. It cannot be declared as a local parameter name or reassigned.

Direct method calls do not create per-instance closures:

```ores
box.get();
```

The runtime resolves the shared class method definition and passes the receiver as the hidden first argument.

When a method is extracted as a first-class value:

```ores
val Fnc<int> callback = box.get;
```

Oreslang creates a small bound-method value containing only the receiver plus method identity. The underlying method definition remains shared by every instance. Calling `callback()` always uses the original `box`; there is no JavaScript-style dynamic `this` rebinding.


## Incremental compilation and code units

Oreslang's canonical compiler output is **decomposable**. A monolithic native executable is a packaging choice, not the semantic compilation unit.

Each source file is a separately versioned **code unit**:

- source digest;
- checked AST / future serialized Ores IR;
- explicit import dependencies;
- package identity;
- zero or more flat modules;
- optional flat source namespace.

With no explicit namespace, the file/code-unit identity is its default package identity. An explicit namespace is written once at the top of the file:

```ores
namespace payments;

import fnc {authorize} from "./auth.ores";

pub fnc charge() => void {
  return;
}
```

Namespaces are flat. `namespace company.payments;` is illegal. Modules are also flat: a module name is one identifier and a module may not contain another module.

The incremental compiler uses separate **source** and **ABI** digests:

1. hash every source unit;
2. derive a deterministic exported ABI digest from public functions/bindings, class public members and static functions, interfaces, type aliases, inheritance, structural markers, and module adherence contracts;
3. rebuild a unit whenever its source or resolved dependency set changes;
4. when a dependency ABI digest changes, invalidate the transitive reverse-import closure conservatively;
5. reuse every importer artifact across implementation-only dependency edits;
6. keep unchanged/unaffected compiled-unit objects intact.

Public inferred bindings are fingerprinted conservatively from their initializer AST until the compiler materializes their inferred exported type in the unit manifest.

This separates code-generation dirtiness from public-contract dirtiness. An implementation edit such as changing a function body from `return 42;` to `return 43;` recompiles that file without recompiling importers when the exported signature is unchanged. Until the cross-unit linker records exact public imported-symbol dependencies, ABI changes intentionally propagate transitively for correctness.

Actors and isolates consume versioned code-unit generations. `HotReloadManager` tracks the active generation **per code-unit id**, so staging `worker.ores` does not replace the active `helper.ores` generation. A hot reload therefore does **not** require rebuilding or replacing every actor: changed units receive new generations, unchanged units remain active/shared, and supervisors migrate actors/requests according to policy.

A deployment may still aggregate many code units into one Native Image for startup/distribution reasons. That aggregate is never the only compiler artifact and must not erase per-unit identities or dependency metadata.

## Static class functions

Instance methods continue to omit `fnc`:

```ores
define class Counter as
  read() => int {
    return self.value;
  }
end
```

Class-level functions are not methods. They are declared with the explicit `static fnc` form:

```ores
define class Counter as
  pub static fnc twice(int value) => int {
    return value * 2;
  }
end

val doubled = Counter.twice(21);
```

A static class function:

- is resolved through the class namespace;
- has no implicit or explicit `self`;
- cannot be invoked through an instance;
- may be extracted as a function value from the class namespace;
- has one shared definition, just like any other named function.

Static data fields are intentionally not part of v0.5 yet; `static` on a class binding is rejected rather than silently acquiring Java-like global mutable state semantics.

## Function types, functors, and arrows

The arrows have distinct jobs:

- `=>` declares the return type of a **named callable**.
- `->` forms a **function type** or **lambda**.

Function aliases can use `typeof fnc`:

```ores
type F = typeof fnc() -> int;
type Predicate = typeof fnc(bool value) -> bool;
```

The shorter inline function type is also valid:

```ores
fnc sink() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Parameter names inside function types are documentation-only; structural function compatibility is determined by parameter/result types.

The canonical lambda syntax is pipe-delimited and block-only:

```ores
fnc find(bool found) => F {
  return || -> {
    return found ? 5 : 6;
  };
}

fnc callback() => ((bool foo) -> void) {
  return |foo| -> {
    stdio.println(foo);
    return;
  };
}
```

Lambda parameters may be inferred from a contextual function type (`|foo|`) or typed explicitly (`|bool foo|`).

There are no expression-body lambdas. Every lambda has braces. When the contextual result type is non-void, every control-flow path must contain an explicit `return <value>;`. Void lambdas may use `return;`.

This means higher-order functions and functors do not introduce a second return convention: named functions, methods, static functions, and anonymous functions all use the same explicit `return` statement semantics.


## Lexical closures

Closures are lexical. A lambda resolves free variables from the scope where the lambda is created, not from the scope where it is called.

```ores
fnc makeCounter() => (() -> int) {
  let int count = 0;

  return || -> {
    count = count + 1;
    return count;
  };
}
```

The returned closure owns the captured lexical environment, so repeated calls observe the same captured `count`.

Capture rules are ownership-aware:

- immutable `Copy` captures are copied into the closure environment;
- non-`Copy` captures transfer ownership into the closure;
- a capture that the closure mutates also transfers the mutable lexical slot into the closure;
- after a move-only/mutable capture is transferred, the outer binding cannot be used;
- an already borrowed value may not be captured by an escaping closure; pass the borrow as a lambda parameter or capture the owner by value.

This makes returned closures safe without retaining raw stack references.

## Parameter immutability and `mut`

Parameters are immutable by default.

```ores
fnc bad(Bar b) => void {
  b.foo = "foobar"; // compile-time error
  return;
}
```

An owned parameter may explicitly opt into mutation by putting `mut` between the type and parameter name:

```ores
fnc change(Bar mut b) => Bar {
  b.foo = "foobar";
  return b;
}
```

`Bar mut b` still receives `Bar` **by value**. For a non-`Copy` value, the caller transfers ownership to `change`; returning the value transfers ownership back.

Local mutation continues to use `let`. `val` and `const` remain immutable.

Class fields follow the same bias: a field declared with `val` or `const` cannot be assigned after construction. Mutable object state must use a `let` field and mutable access to the owning value.

## Ownership, moves, and borrows

Oreslang uses Rust-style affine ownership for mutable/heap-backed values.

The initial `Copy` family is:

- integer types;
- floating/decimal/complex scalar types;
- booleans;
- immutable strings.

Class instances, arrays/lists, object records, and closures are move-only by default.

A by-value binding, argument, or return consumes a non-`Copy` value:

```ores
fnc consume(Bar value) => void {
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  consume(b);
  // b.foo; // compile-time error: use of moved value
  return;
}
```

Shared immutable borrowing uses `&T`:

```ores
fnc inspect(&Bar value) => void {
  stdio.println(value.foo);
  return;
}
```

Exclusive mutable borrowing uses `&mut T`:

```ores
fnc change(&mut Bar value) => void {
  value.foo = "changed";
  return;
}

fnc example() => void {
  let Bar b = new Bar();
  change(&mut b);
  stdio.println(b.foo); // owner is usable again after the call
  return;
}
```

Borrow rules:

- any number of immutable borrows may coexist;
- a mutable borrow is exclusive;
- mutation/move of the owner is forbidden while any borrow is active;
- reading the owner is forbidden while an exclusive mutable borrow is active;
- mutable borrowing requires a mutable owner;
- a borrow of a local value may not escape the owner's lifetime;
- temporary call borrows end at the call boundary;
- borrows stored in local bindings remain active until that binding's lexical scope ends.

The initial checker is deliberately conservative around complex branch/loop lifetime shortening. It rejects uncertain aliasing rather than silently accepting it. Later control-flow/NLL work may accept more programs without weakening these invariants.

Structural parameters remain read-only views and therefore do not consume the supplied value.

## Multi-threaded targets

Actors/isolate message passing remains the primary concurrency model, but the ownership contract is backend-independent.

The same compiled program can target a secondary multi-threaded runtime because:

- mutable state has one owner unless temporarily accessed through an exclusive `&mut` borrow;
- shared aliases are immutable;
- move-only values cannot remain accessible from both sides of an ownership transfer;
- closures cannot smuggle an outstanding stack borrow into a longer-lived task;
- actor messages continue to cross actor boundaries only through the existing frozen/sendable contract.

When explicit thread/task spawning is added, cross-thread transfer will require move semantics and a `Send`-equivalent capability; shared cross-thread references will additionally require a `Sync`-equivalent guarantee. Those marker traits are intentionally a future surface feature—the current source language has no ambient raw-thread API, so there is no unchecked escape hatch to bypass ownership.


## Native core values and typed structs

Guest-visible `List<T>`, `Map<K,V>`, object records, `DynamicStruct<T>`, typed structs, files, networking primitives, and thread primitives are Oreslang runtime values. Their storage and OS operations are implemented by the native runtime libraries rather than by `java.util`, `java.io`, `java.net`, or `java.lang.Thread`.

```ores
define struct Point as
  pub let i64 x;
  pub let i64 y;
end

val p = new Point(10, 20);
```

A typed struct is nominal and fixed-shape: it cannot gain fields at runtime. In v0 it is field-only and does not inherit or implement interfaces. Field mutation follows the normal `val`/`const`/`let` rules.

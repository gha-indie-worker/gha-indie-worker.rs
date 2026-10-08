# `trap`: Option-valued non-throwing callables

This document defines the Oreslang contract for the `trap` callable modifier
and its interaction with `throw`, `raise`, `panic`, async/Future execution,
select, actors, and runtime cancellation.

The central rule is intentionally small:

> A call to a `trap` callable never exposes an ordinary `throw` to its caller.
> Success is `Some(value)`; an uncaught ordinary throw is `None`.

`raise`, `panic`, runtime cancellation, forced termination, and fatal host
failures are not ordinary throws and are not converted to `None`.

## Surface syntax

```ores
pub trap fnc load_user(UserId id): User {
  return db_load(id);
}

pub async trap fnc fetch_user(UserId id): User {
  return await remote_load(id);
}
```

Instance methods use the same modifier contract:

```ores
define class Loader as
  pub trap load(UserId id): User {
    return self.lookup(id);
  }
end
```

`trap` is part of the callable type/effect contract and must survive imports,
function values, callbacks, generics, interface/trait slots, virtual dispatch,
tree shaking, JIT/AOT lowering, and interop metadata.

## Call result type

The declared return type remains the success type.

For a synchronous trap callable:

```text
trap fnc(): T
```

the call expression type is:

```text
Option<T>
```

Example:

```ores
const user: Option<User> = load_user(id);
```

There is no two-slot trap tuple and no compiler-owned `TrapResult<T>` wrapper.

### Nested Option is deliberate

If the successful value is already optional, the trap layer does not flatten it:

```ores
trap fnc lookup(UserId id): Option<User> {
  ...
}

const result: Option<Option<User>> = lookup(id);
```

The meanings are distinct:

```text
None             => the trapped computation threw
Some(None)       => the computation succeeded and returned no user
Some(Some(user)) => the computation succeeded and returned a user
```

Automatic flattening would destroy that information and is forbidden.

### Void

A `trap fnc ...: void` has call type `Option<void>`.

The compiler/runtime may represent successful void internally as a unit/null
payload, but the language-level states are still:

```text
Some(void) => completed successfully
None       => ordinary throw escaped the function body
```

`Option<void>` is therefore a valid compiler-produced type. The type system
must support observing it with `is_some()` / `is_none()`; no useful value is
produced by unwrapping it.

## Control-flow channels

Oreslang keeps three exceptional control effects distinct:

```text
throw  -> ordinary recoverable error
raise  -> deliberate non-trappable exceptional escape
panic  -> invariant/runtime non-trappable escape
```

The trap rule is:

```text
ordinary return -> Some(value)
uncaught throw  -> None
raise           -> bypass trap
panic           -> bypass trap
cancellation    -> bypass trap
termination     -> bypass trap
```

A local explicit `try/catch` may consume a throw before it reaches the trap
boundary.

Returning an `Error`, `Result.Err`, or any other error-looking value is still
ordinary data. Only the control-flow `throw` effect becomes `None`.

## Error detail is intentionally not carried by trap

`trap` is the concise, lossy "did this complete normally?" boundary.

Code that needs error details should use an explicit typed `Result<T, E>`,
an explicit `try/catch`, or another API whose return type carries structured
error data.

This avoids the old awkward shape:

```ores
[const [result], const err] = foo();
```

and replaces it with ordinary Option handling:

```ores
const result: Option<User> = foo();
```

## Async trap

For:

```ores
pub async trap fnc fetch(): Payload {
  ...
}
```

the call expression type is:

```text
Future<Option<Payload>>
```

The trap boundary spans the complete asynchronous computation, including code
resumed after every `await`.

- success settles the Future with `Some(payload)`;
- an ordinary uncaught throw before or after suspension settles the Future
  successfully with `None`;
- `raise` and `panic` bypass trap and retain their distinct control identity;
- runtime cancellation stays cancellation rather than becoming `None`;
- forced actor/isolate/sandbox termination stays a supervision/termination
  outcome rather than becoming `None`.

Awaiting an async trap Future re-emits any non-trappable raise/panic/cancellation
according to the normal runtime rules.

A trap callable whose declared success type is already `Option<T>` therefore
returns `Future<Option<Option<T>>>` when async.

## Future composition

Oreslang's Future is runtime-owned. Reactive chaining must preserve the same
scheduler rule as `await`: completion threads may settle a Future but may not
execute guest callbacks inline.

The language/runtime Future surface should support the same useful split used by
modern Vert.x APIs:

```text
map       : (T -> U)         -> Future<U>
compose   : (T -> Future<U>) -> Future<U>
flatMap   : alias of compose
onSuccess : (T -> void)      -> Future<T>
```

These callbacks execute as Ores scheduler/actor turns. They are not Java
`CompletableFuture` callbacks and do not inherit producer-thread affinity.

## Select interaction

Value-returning dynamic selection follows the Option/Future convention:

```text
select from cases     -> Option<SelectResult>
nb select from cases  -> Future<Option<SelectResult>>
try select from cases -> Option<SelectResult>
```

A blocking `select from` yields the current Ores continuation rather than
parking a carrier thread. When an arm wins, it resumes and returns
`Some(SelectResult)`.

`try select` returns `None` when no case is immediately ready and leaves no
waiter behind.

### Throws inside static select arms

A static branch-select is a control-flow construct:

```ores
select {
  case readch input: val value {
    ...
  }
}
```

Its selected arm is required to be non-throwing at the arm boundary.

- a throw handled by a local `try/catch` is allowed;
- a call to a `trap` function is allowed because the throw is converted to
  Option data before returning to the arm;
- an ordinary throw that can escape the selected arm is a compile-time error;
- `raise` bypasses select and continues to the nearest matching recover boundary;
- `panic` bypasses select and follows panic/recover/supervision policy;
- runtime cancellation/termination bypasses the arm and remains runtime control.

This prevents `select` from gaining a hidden exception channel while still
preserving explicit nonlocal control effects.

## Cleanup

`defer` / `finally` run during unwinding before a trap boundary produces
`None`.

Cleanup must not downgrade stronger control signals. The runtime preserves the
ordering:

```text
fatal/termination > panic > raise > throw > return
```

A cleanup throw may replace a normal return, and the nearest trap may then turn
that throw into `None`. A cleanup throw must not convert an in-flight
raise/panic/cancellation into `None`.

## Callable compatibility

`trap` is part of callable metadata.

These are distinct contracts:

```text
fnc(): T
trap fnc(): T
```

At the call site they expose `T` and `Option<T>` respectively.

V1 should require exact trap compatibility for overrides/interface slots and
function-value assignments unless an explicit safe adapter is defined.

## Actors and execution domains

A trap boundary is dynamic to one execution domain. It never implicitly crosses
an actor, isolate, process, or detached-task boundary.

An unrecovered `raise` or `panic` inside an actor follows actor supervision.
Untrusted actor resource cancellation/termination cannot be swallowed by
`trap`.

## Host/native failures

Only failures explicitly classified as ordinary Ores `throw` may become
`None`.

VM-fatal errors, OOM/stack corruption, process termination, scheduler
interruption/cancellation, and unknown native failures must not be blanket
converted by a broad `catch (Throwable)`.

## Required implementation work

1. reserve and parse `trap` on supported callables;
2. store trap metadata in AST/symbol/callable types;
3. type synchronous trap calls as `Option<T>`;
4. preserve nested Option without flattening;
5. support compiler-produced `Option<void>`;
6. lower ordinary uncaught throw at the boundary to `None`;
7. lower successful return to `Some(value)`;
8. type async trap calls as `Future<Option<T>>`;
9. preserve raise/panic/cancellation/termination as distinct bypass channels;
10. enforce trap compatibility across function values/interfaces/overrides/imports;
11. reject escaping ordinary throw from static select arms;
12. preserve the contract through optimization, actors, JIT/AOT, and interop.

## Required tests

Compile/runtime coverage must include:

- trap success -> `Some(value)`;
- trapped ordinary throw -> `None`;
- `trap fnc(): Option<T>` -> `Option<Option<T>>`;
- trapped void -> `Option<void>`;
- async trap success -> `Future<Option<T>>`;
- throw after await -> Future settles with `None`;
- raise/panic bypass sync and async trap;
- cancellation bypasses trap;
- local try/catch inside trap handles throw before the boundary;
- returned Error/Result values remain data;
- interface/override/function-value trap compatibility;
- static select arm rejects an escaping ordinary throw;
- static select arm permits locally caught throw and handled trap Option;
- raise/panic in a select arm bypass select;
- dynamic select returns `Option<SelectResult>`;
- `nb select` returns `Future<Option<SelectResult>>`;
- callbacks used by Future.map/compose/onSuccess execute only on an Ores scheduler
  or actor turn.

## Core invariant

```text
throw  -> nearest catch, otherwise nearest trap -> None
raise  -> bypass catch/trap/select -> nearest recover/supervision
panic  -> bypass catch/trap/select -> nearest recover/supervision
cancel -> runtime cancellation channel, never None
```

That distinction is semantic and must not be erased by parsing, optimization,
interop, actor transport, or backend lowering.

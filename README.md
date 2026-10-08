# Oreslang

Oreslang is a statically typed GraalVM/Truffle language with nominal typing by default, explicit structural-call opt-ins, actor-oriented concurrency, hot-loadable code generations, and deny-by-default isolate capabilities.

This repository contains the Java/Truffle reference implementation.

The language is intentionally opinionated:

- static nominal typing by default, with explicit structural compatibility at selected call boundaries;
- private functions by default (`fnc`), with `pub` for exported functions;
- class methods omit `fnc` and have an implicit `self` receiver;
- class construction uses a single explicit `constructor(...)` declaration (never a Java-style class-name constructor); top-level classes/constructors are file-private, while module classes/constructors are private by default and may be `pub`;
- classes cannot nest inside classes; class bodies contain fields, an optional constructor, methods, and `static fnc` members only;
- one return value only (tuples/arrays/records are ordinary single values);
- `val`, `const`, and `let` are the only variable declarations;
- private actor state is logically confined in the current backend; shared actors may access explicit synchronized shared cells; protected per-actor heaps are under development;
- immutable/sendable values may be message-passed, and explicitly frozen regions may be shared read-only;
- host access is denied and Oreslang APIs are capability-gated by default; the current actor kinds alone do not establish an OS sandbox;
- JIT, AOT/interpreter, and AOT-host + guest-JIT hybrid execution profiles;
- file-granular incremental compilation with stable code-unit/package identities and reverse-dependency invalidation;
- flat optional file namespaces and flat modules (neither may nest);
- class-level `static fnc` functions separated from receiver methods;
- first-class function aliases/types and block-only `|args| -> { ... }` lambdas;
- lexical closures with persistent captured environments;
- affine ownership, move checking, borrows, immutable-by-default parameters, and `Type mut name` owned-mutation syntax - see [ft borrow, ft copy, ft take, ft share]
- hot reload creates a fresh versioned guest context/generation without requiring FFI or dynamic native libraries;
- direct method calls reuse shared class method definitions; instance/actor methods are direct-call-only, and callbacks use explicit lambdas instead of implicit bound-method values;
- multiple named modules may appear in one source file;
- explicit `return` statements;
- generics, tuples, arrays, complex numbers, futures/`await`, lambdas, `defer`, and `try/catch/finally` are language-level features.

The first implementation is developed on a feature branch and will land with an executable Truffle skeleton, grammar/specification, examples, tests, and CI.

Native inbound HTTP capabilities and actor-owned request transfer are documented in [docs/HTTP_SERVER.md](docs/HTTP_SERVER.md).

The implemented actor scheduling guarantees, native descriptor-transfer experiment,
and remaining physical-isolation work are tracked in
[Actor physical boundaries](docs/runtime/actor-physical-boundaries.md).

Async filesystem, TCP and HTTP client APIs: [usage and execution guarantees](docs/ASYNC_IO.md).

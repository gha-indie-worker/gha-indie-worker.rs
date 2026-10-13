# Native-first Oreslang core implementation

Oreslang's core implementation is **Oreslang-first**.

The host JVM and Graal/Truffle remain implementation substrates for parsing, AST/Truffle nodes,
embedding, compilation, context management, and bootstrapping. They are not the preferred place to
implement Oreslang standard-library semantics.

## Layering

1. **Oreslang source owns language/library semantics**
   - canonical source files live under repository-root `stdlib/**/*.ores` and are imported as `std/*`;
   - functions, collections algorithms, streams, protocol logic, networking state machines, file APIs,
     synchronization APIs, and user-visible behavior should be written in Oreslang where the language
     can express them safely;
   - importing a core module must not implicitly grant Java host authority.

2. **Native boundary owns OS/device primitives**
   - low-level syscalls, polling, sockets, files, clocks, memory mapping, native carrier operations,
     and accelerator launch primitives should cross a narrow capability-checked native ABI;
   - JNI is the current preferred JVM bridge where a host transition is required;
   - the Oreslang ABI must not depend on JNI object identity or Java class layouts so another bridge
     can replace JNI without changing source semantics.

3. **Java owns Truffle/embedding glue**
   - parser/compiler/Truffle nodes and Graal context integration may remain Java;
   - Java may adapt native results into runtime-internal handles;
   - Java must not become a second stdlib implementation hidden behind Ores APIs.

4. **Java interop is application interop, not core implementation**
   - `java { ... }`, `do java { ... }`, and `java:` imports remain explicitly capability-gated;
   - packaged `std/*` modules reject all three forms;
   - untrusted/adversarial actors never gain Java authority through stdlib imports.

## Migration priorities

Highest priority migrations are runtime surfaces that currently depend on Java semantics:

- actor/scheduler carriers -> native carrier/JNI backend while preserving Ores scheduler policy;
- blocking I/O -> native readiness/proactor layer that completes Ores Futures;
- filesystem/network/socket/fd -> Oreslang APIs over narrow native handles;
- mutex/rwlock/timer primitives -> Ores scheduler/native primitives rather than Java locks/timers;
- ECS/Region storage -> primitive/native columns and device buffers rather than `Object[]`;
- numerical kernels -> Ores-facing contracts over native CPU/GPU kernels instead of exposing Java math objects;
- rx/stream operators -> `.ores` modules over OresFuture/Awaitable rather than Java callback libraries.

## Transitional Java code

Existing Java runtime code is technical debt, not the semantic target. A PR may temporarily use Java
for a missing primitive only when it:

- documents the missing native primitive;
- keeps the guest-visible contract Ores-owned;
- does not expose the Java object to guest code;
- preserves AOT/native-image behavior;
- includes a migration point to the native ABI;
- never uses Java interop capability as a shortcut for core behavior.

## Built-in core modules

`LinkedProgramRunner` resolves imports beginning with `std/` from compiler-owned Oreslang source
resources. The canonical editable sources are `stdlib/**/*.ores`; Maven packages them under the
collision-resistant classpath prefix `dev/oreslang/std/`. These modules are parsed, type-checked, linked, tree-shaken, and executed like ordinary
Oreslang units.

Core modules fail closed if they contain:

- a `java:` import;
- `java { ... }`;
- `do java { ... }`;
- a non-`std/*` dependency.

This makes "native-first" mechanically enforceable rather than a style recommendation.


## Review gate

Every new user-visible runtime/library feature should answer these questions before adding Java:

1. Is this parser/type/effect/lowering, scheduler/isolate/GC, capability enforcement, or a native-handle adapter?
2. Can the same behavior be expressed safely as an Oreslang `stdlib/**/*.ores` module?
3. If Java is required, is the Java surface smaller than the user-visible feature?
4. If JNI/native code is required, are descriptors, pointers and device handles hidden behind opaque generation-safe values?
5. Do JIT, AOT, hybrid and isolate modes execute the same Oreslang stdlib semantics?

If (2) is yes, the behavior belongs in Oreslang first. Java convenience libraries are not a reason to bypass the stdlib boundary.

The intended layering is:

```text
application .ores
  -> std/*.ores
  -> narrow runtime intrinsic
  -> Java/Truffle host kernel
  -> JNI/native OS or device primitive when required
```

## Resource provenance and AOT packaging

- `std/*` source is compiler-owned: a local `./std/core.ores`, project source root,
  or `ORESLANG_PATH` entry must not override a packaged stdlib module.
- Duplicate resources with the same packaged `dev/oreslang/std/**/*.ores`
  path are rejected instead of relying on classpath lookup order.
- The loader rejects overlong paths (>4096 characters), deep module paths (>32 segments),
  malformed UTF-8, oversized (>1 MiB) sources and missing resources.
- GraalVM Native Image includes source assets through
  `META-INF/native-image/dev.oreslang/oreslang/reachability-metadata.json`,
  preserving the existing native JNI metadata; do not introduce a competing
  legacy IncludeResources regex.
- Host-side native library tests exercise the loader. Complete GraalVM/Native
  Image integration is a separate executable validation gate.

## Compiler-owned module registry

The loader keeps an explicit closed list of approved packaged `.ores` modules.
A resource added to an application classpath under `dev/oreslang/std/`
cannot invent a new `std/*` import. When adding a standard-library module,
update the loader registry together with the native Oreslang source.
The JUnit source/registry equality test rejects both forgotten registration
and stale registry entries.

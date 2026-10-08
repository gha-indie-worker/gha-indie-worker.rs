# Native build capabilities

The deployment choice is made at build time:

| Build | Command | Default execution policy |
| --- | --- | --- |
| JVM | `mvn package` | `jit` |
| AOT-only native | `mvn -Pnative-aot -DskipTests package` | `aot` |
| Hybrid native | `mvn -Pnative-hybrid -DskipTests package` | `hybrid` |

Native builds require JAVA_HOME pointing to a GraalVM toolchain matching the
pinned Graal/Truffle SDK, plus the platform C toolchain. Temurin/OpenJDK alone do
not include native-image. The outputs are target/ores-aot and target/ores-hybrid.
Use `--build-info` to inspect the capability embedded in the executable.

`NativeBuildMode` is initialized while Native Image builds the executable. The
image-generation property `ores.native.build-mode=aot|hybrid` is mandatory. It
cannot be changed by setting a system property after compilation. An AOT image
rejects `--mode=jit` and `--mode=hybrid`; a hybrid image accepts `--mode=hybrid` or
`--mode=aot`. AOT execution policy disables engine compilation when the engine
supports it. JVM execution profiles remain available for policy testing, but do
not turn the JVM process into a native executable.

AOT-only uses Truffle's fallback runtime: the interpreter and reachable Java
runtime libraries are native code, without a guest JIT. **This does not lower
all Oreslang handlers to application-specific machine code at build time.**
Guest source is still parsed/typechecked and interpreted. Hybrid includes the
Truffle optimizing runtime; its throughput must be measured rather than assumed.

Native binaries need neither a JDK nor a JVM installation on their target. They
still depend on platform OS libraries. Our pthread carrier library is currently
shipped alongside the executable (`liboresthread.dylib` or `liboresthread.so`),
not statically linked. Pass `-Dores.thread.native.path=/absolute/path/to/library`
and `-Dores.runtime.carriers=native` to require that backend rather than permit
the development fallback. JNI reachability metadata is included in the runtime.

A distribution must bundle its source import graph or embed it as resources,
pin native libraries, include license notices, and be built per OS/architecture.
The REST demo supplies an application-specific native launcher that embeds the
fixed Ores route graph and extracts it to a private temporary directory. No
Python, Git, Maven, source checkout, or network dependency resolution is required
to run that distribution. Its runtime port/data-directory settings do not change
the build's compilation capability.

Java source islands require JVM mode and cannot be dynamically compiled in an
AOT/hybrid executable. Precompiled, explicitly registered interop needs its own
reachability metadata. A native build succeeding is not evidence that every
interop extension or untrusted isolate path works: run those deployment-specific
tests separately.

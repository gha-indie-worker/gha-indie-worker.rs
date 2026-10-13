# Core performance flight recorder

Core runtime diagnostics, independent of `oreslang-otel` and guest Ores code.
Both modes are disabled by default. Neither is distributed tracing or application logging.

## Enable

```sh
oreslang-compiler --core-perf --platform=server program.ores
oreslang-compiler --core-debug --platform=server program.ores
# Use both switches when correlating timing and numeric diagnostic events.
# Or with flags-2-env's canonical .cli-flags.toml:
# eval "$(flags2env export -- oreslang-compiler --core-perf)"
# ORES_CORE_PERF=true oreslang-compiler --platform=server program.ores
```

The `--no-core-perf` and `--no-core-debug` switches override
`ORES_CORE_PERF=true` and `ORES_CORE_DEBUG=true` respectively. The canonical
flags-2-env schema filename is `.cli-flags.toml`, **not** `.cli-args.toml`.
The core binary recognizes the switch directly; flags-2-env need not be linked
into the JVM or invoked for the recorder to work.

Performance and debug each retain their first 8192 events in separate
preallocated buffers. Reservations are bounded and cannot overflow. The
shutdown summary distinguishes published events from in-flight reservations.
The first 8192 completed timing phases use the performance buffer: no JSON
formatting, locks, strings, file writes, or guest object allocations occur on
instrumented requests. The disabled hot path checks one volatile reference;
the enabled path captures monotonic timestamps and atomically reserves a slot.
The fixed-capacity buffer intentionally stops collecting when full so warm
traffic cannot evict the first cold-start samples.

Backend metadata (native pthread versus JVM thread pool, including configured
actor-pool sizes) is written once at dump time.

Events are serialized as `ores-core-perf.v1` JSON Lines to **stderr on clean
JVM shutdown** (Ctrl-C/SIGTERM). SIGKILL and crashes do not invoke shutdown
hooks. Each phase provides `start_ns` relative to recorder installation,
`duration_ns`, and `thread_id`; no request body, path, header, or other
user-controlled data are included. Different phases can overlap and **must not
be summed** as mutually exclusive CPU time.

With `--core-debug`, `ores-core-debug.v1` events contain only fixed event
names, relative monotonic timestamp, a numeric value and host thread ID.
Instrumentation sites should use `if (CoreDebug.enabled())` for expensive
argument construction; never look up guest env vars or call stdio on the hot path.
The initial events cover actor-pool creation, listener readiness, and HTTP
executor task starts. For Java embedding, configure diagnostics before
creating the Ores context; reconfiguring a recorder pauses/resumes sampling
without discarding buffered records. Shutdown output is not crash-safe.

The current instrumentation includes CONTROL and actor-pool startup, context
startup, HTTP listener setup, request admission/dispatch, the deadline-timer schedule and actor spawn, actor readiness and
claim, and HTTP / standard-library asynchronous I/O queue and work. HTTP
`receive` timing begins **inside** the virtual-thread handler and therefore
does not capture how long the JDK's transport waits before submitting to
its executor. In diagnostic mode, `http.executor.queue` measures the interval
from JDK executor submission until the worker begins running the task; this
adds a wrapper only when diagnostics are enabled.
A first-request delay not accounted for by phases should be investigated with
JFR (thread start, scheduling and JIT compilation) before adding a prewarm.

`stdio.write` measures the single-argument standard-library stdout print/flush
or println call, after converting its already-evaluated argument to text. It
does not include guest JSON construction, integer encoding, or variadic
`stdout.log`. `http.lifetime` measures admission through transport closure and
actor finalization, captured exactly once when the active exchange releases
its admission permit, before completing its observation future. Both phases
use fixed names and numeric samples; they retain no payload or correlation ID.

These boundaries help distinguish slow guest telemetry construction from the
actual console write and the request's transport/actor lifetime. The lifetime
can overlap every request phase. Userland request spans can end later, after
the supervisor resumes and exports them.

## Analyze

```sh
# After stopping the instrumented server:
jq -r 'select(.schema == "ores-core-perf.v1" and .kind == "phase")
  | [.phase, (.duration_ns/1000000), (.start_ns/1000000)] | @tsv' server.log
```

`--core-perf` is a diagnostic mode; timing and buffer writes themselves can
perturb very short operations. Always compare both enabled and disabled runs.

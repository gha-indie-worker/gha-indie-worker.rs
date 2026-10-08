# Actor execution, heaps, shared domains, and HTTP ownership

Status: implementation in progress. Contract agreed with the project owner on
2026-10-07. This document distinguishes implemented runtime behavior from native
transport experiments and remaining backend work. Native AOT is a build mode,
not evidence of memory isolation.

## Required contract

1. One actor-wide execution lease covers initialization, receive, timers,
   continuations, errors, supervision handlers, and guest cleanup. No two carrier
   threads may execute that actor's code simultaneously, even across pools.
   A suspended callback may resume only by mailbox admission and lease acquisition.
2. Actors have stable scheduling placement. Hard CPU affinity is explicit and
   fail-closed when requested; a scheduling lane is not a physical CPU guarantee.
3. Every actor has its own default allocation heap. Isolated actors additionally
   have a protected address-space boundary. Shared actors also have private heaps;
   only explicit shared regions cross those private boundaries.
4. Shared children and grandchildren inherit the shared domain. A supervisor can
   participate explicitly. Domain ownership and lifetime are independent of one
   parent's lifetime; shared region reclamation waits for all leases to end.
5. All actor invocation is mailbox/channel driven. Shared actors may access
   explicitly synchronized shared data concurrently. This is mailbox-only
   invocation, not a claim that shared data cannot convey information. I/O and
   completion threads publish messages; they cannot invoke guest callbacks.
6. Isolated HTTP actors must own their transferred request/response endpoint.
   Untrusted actors receive bounded serialized messages by default, with no raw
   descriptors. Any later descriptor capability requires a platform-specific
   sandbox design and its own tests; a trust enum is not a sandbox.

## What this branch implements

- Both ordinary user mail and the prioritized continuation lane use bounded
  `ChannelRuntime.Channel` transports. Control mail retains its independent quota;
  user traffic cannot consume continuation headroom. Termination drains both.
- Stable actor lanes by default, across shared/private/untrusted dispatchers.
  The actor receives its lane once at creation. Rescheduling never steals it.
  Lanes share a bounded dispatcher admission budget. Blocking one lane can delay
  its other actors, so guest I/O must suspend instead of blocking the carrier.
- Optional hard CPU binding on Linux via `sched_setaffinity`, validated before
  worker allocation and applied before entering each actor execution boundary.
  A later binding failure fails the actor rather than executing it unbound.
- Host-only `placementFor(actor)` diagnostics distinguish lane, CPU and unbound
  placement. The diagnostic is an assignment, not a claim of private heaps or an
  exclusive CPU reservation.
- Experimental Unix mailbox/FD transport in `src/main/c/oresmailbox.*` and a
  separately exec'd worker integration test. This transport is **not yet wired to
  Oreslang source actors, NativeHttp, Spin, or the REST demo**.

Configuration:

```sh
# Default: stable logical lanes, no OS core-binding promise.
-Dores.actor.affinity=lane

# Linux: mandatory CPU binding; CPUs must be allowed by the caller's affinity mask.
-Dores.actor.affinity=cpu -Dores.actor.cpus=2,3

# Explicit compatibility/measurement mode; does not satisfy stable placement.
-Dores.actor.affinity=none
```

Hard affinity is unavailable through this backend on macOS and fails explicitly.
Do not describe macOS lane placement as hard CPU affinity. Multiple actors and
lanes can share a CPU; affinity does not reserve it exclusively. The configured
CPU list currently applies to each actor-kind pool, not separate cpusets per kind.

## Lifecycle control is independent of data capacity

The three channels above are not a complete description of actor lifecycle state.
`self.end()` requests a local lifecycle transition directly; it does not publish an
output or reserve an inbox/continuation slot. It seals admission and cancels queued
work. Finalization still waits for the current execution boundary, child teardown,
and registered cleanup. It does not wait for the output consumer to drain data.
Already-buffered output remains readable after `actor.done()` completes; stream
exhaustion and actor termination are distinct observations. An extra output emitted
by cleanup into a full stream fails cleanup and completes termination exceptionally,
rather than waiting for the consumer.

For remote protected workers, reserve a lifecycle signaling path independent of
body/output/continuation credits. Idempotent stop/cancel state can be latched and
wake the scheduler; it need not allocate a fourth general-purpose Channel. Any
cross-actor guest callback induced by a signal still runs under the recipient's
execution lease. OS-level force termination is a separate supervisor capability.

Erlang is an influence, not this runtime's channel layout: ordinary messages are
also signals, trapped exit and monitor signals may become queued messages, and
signal ordering must be respected. Runtime signal handling does not imply every
exit notification overtakes application messages. See the official
[process and signal semantics](https://www.erlang.org/doc/system/ref_man_processes.html).

## Physical heap backend decision

Start with exec'd workers as the reference protected backend. Each live isolated
actor needs its own address space; putting several isolated actors in one worker
would weaken this contract. Warm workers can amortize startup, but reassignment
must recreate the address space, or demonstrate complete VM teardown and absence
of retained authority. Never fork a running multithreaded JVM and continue guest
execution in the child; use a controlled spawn/exec launcher.

For shared actors, use private worker heaps plus explicit mapped shared regions
as the initial reference design. Shared domain handles carry stable region IDs,
generations, bounds, and permissions. Shared references use validated offsets or
opaque IDs, never Java references or raw cross-process pointers. A child's spawn
message grants its inherited domain membership; a grandchild receives the same
domain ID. Isolation boundaries strip that membership. Domain records must not
contain actor-local objects that can escape through a shared graph.

Shared mutation needs process-safe synchronization with crash recovery. Heap GC
must treat region handles as explicit leases. Do not reclaim a mapping while
another actor or in-flight mailbox envelope holds a lease. Cross-domain cycles,
owner death, outstanding I/O, and cleanup ordering require dedicated tests.

A Native Image isolate backend may later provide separate managed heaps with less
process overhead. It needs a C ABI and explicit serialized/capability boundaries;
ordinary Truffle Contexts and the current Java ActorMemorySlice are insufficient.
A same-process native isolate is not automatically an OS security boundary against
arbitrary native code. No claim is made that the current custom Oreslang language
already supports Graal's UNTRUSTED sandbox policy.

## HTTP endpoint ownership

A descriptor is for an open kernel resource, not inherently one HTTP request.
HTTP keep-alive, pipelining, HTTP/2 multiplexing, TLS state, and bytes already read
by the parser prevent arbitrary request-level migration of a connection socket.
The current JDK HttpExchange has no supported raw-FD export API. Do not use Unsafe
or reflective JDK field access to extract it.

Support two explicit endpoint kinds:

- **Exclusive connection**: a native transport transfers a quiescent socket plus
  parser/buffer state, with no aliases or pending I/O. The initial direct mode
  should handle one plaintext HTTP/1.1 request and close the connection. Reusing a
  connection requires a separate return-ownership protocol, not implicit aliasing.
- **Request-scoped endpoint**: the connection broker retains multiplexing/TLS
  state. A real socketpair endpoint is transferred into the isolated worker.
  Bounded request/body/response frames travel over that channel; the actor owns
  this endpoint's descriptor, not the underlying client connection. This supports
  per-request isolation without giving one handler other requests' bytes.

Untrusted actors initially use serialized metadata and bounded body/response
chunks through a broker. Enforce cancellation, deadlines, response headers,
body limits and backpressure at that broker. Never give the HTTP listener, TLS
keys, shared-domain mapping descriptors, or unrestricted client sockets to an
untrusted worker by default. Process separation alone does not restrict syscalls,
filesystem access, same-user process access, or ambient networking.

### Native transfer primitive implemented here

1. Sender owns the sole application-accessible endpoint and stops I/O.
2. Send an OFFER with SCM_RIGHTS and a nonzero transfer ID over a dedicated
   connected Unix datagram mailbox.
3. Invalidate the sender's descriptor variable and close its descriptor.
4. Send COMMIT with the same ID. The receiver does not expose the received FD
   until it validates this commit.
5. Receiver owns the FD; it is close-on-exec. Invalid ancillary data, wrong frames,
   missing commit, and timeout close any received descriptors.

SCM_RIGHTS duplicates a kernel reference; it is not an atomic kernel move. There
is a brief staged overlap of kernel references, but the trusted receiving runtime
must not expose or use its reference before commit. This does not defend against
a malicious receiver implementing its own recvmsg; that is why raw transfer to
untrusted workers is disabled.

The primitive requires one sender/receiver per direction, a private connected
socketpair, a total operation deadline, and a maximum 4096-byte message. It does
not serialize arbitrary JVM objects. Never multiplex concurrent transfer calls
on one endpoint. After a protocol failure discard the mailbox; a failure after
OFFER may consume ownership, and `*descriptor == -1` is authoritative. A queued
commit is not a receipt or completed HTTP response. Integration must add actor
incarnation/monotonic transfer IDs, delivery receipts, lifecycle supervision,
resource quotas and idempotent cleanup. Do not blindly retry a moved request.

The test worker executes a small native fixture, not Oreslang guest code. Its
successful HTTP exchanges demonstrate OS descriptor transport only, not finished
source-actor isolation. Linux has atomic close-on-exec receive support; macOS uses
fcntl immediately after receive. The future process launcher must serialize FD
creation/receipt against spawning or apply an explicit child FD allowlist.

## Implementation sequence and acceptance evidence

| Step | Status | Acceptance evidence / remaining work |
|---|---|---|
| 1. Record contract and research | Done | This document and primary sources below |
| 2. Channel-backed continuation mailbox | Implemented | Existing cancellation/capacity suite plus cross-kind execution test |
| 3. Stable lanes and explicit affinity | Implemented | Same carrier across lifecycle/resumptions; unsupported CPU configuration fails |
| 4. Native FD transfer transport | Experimental | Exec worker handles real HTTP sockets; malformed/failed transfers reclaim FDs |
| 5. Protected source-worker bootstrap | Pending | Run real source actors in independent address spaces; validate code identity and bounded wire codec |
| 6. Shared actor private heaps/domains | Pending | Parent/child/grandchild see one explicit region; private allocations remain inaccessible |
| 7. HTTP endpoint integration | Pending | Wire native/broker endpoints into net/http, Spin dispatch and route actors |
| 8. Untrusted worker sandbox | Pending | Serialized-only default; deny ambient filesystem/network/process access; quotas |
| 9. GC and failure hardening | Pending | Worker crash at every transfer phase, expired handles, region cycles, parent death and stack attribution |
| 10. End-to-end native delivery | Pending | Both AOT modes, REST curls, affinity/heap/PID logs, cold/warm benchmarks and adversarial stress |

Run `./scripts/test-native-mailbox.sh` for the standalone native transport checks.
Run `mvn test` for runtime regressions. A standalone Linux/macOS native-test CI
matrix is prepared separately; publishing it requires GitHub workflow scope. Linux CPU-binding verification is included
in the native test but must actually run on Linux; macOS validates ENOTSUP only.

## Lessons from established runtimes

- [Actix contexts](https://actix.rs/docs/actix/context/): address/mailbox dispatch
  and bounded admission are useful patterns. Its documented notify/do_send
  exceptions are not our desired contract; internal work also needs a bounded
  mailbox lane here.
- [Rust ractor](https://docs.rs/ractor/latest/ractor/): distinguish work messages,
  supervision, graceful stop and urgent termination. Priorities must not permit
  concurrent guest execution. Rust Send bounds are not separate-heap protection.
- [Akka Typed interaction patterns (Scala/Java)](https://doc.akka.io/libraries/akka-core/current/typed/interaction-patterns.html):
  adapt async outcomes into messages to self rather than mutate actor state in
  arbitrary future callbacks. Dispatcher threads are an execution resource,
  not the actor's identity.
- [Vert.x context guide](https://vertx.io/docs/guides/advanced-vertx-guide/): context
  confinement and event-loop affinity inform our stable lanes. Never block the
  event loop. A fixed thread does not itself imply fixed CPU placement.
- [Erlang process efficiency](https://www.erlang.org/doc/system/eff_guide_processes.html):
  per-process heaps and copying ordinary message data inform our private heap
  model; shared binaries/literals are explicit exceptions. BEAM processes are
  not separate OS processes, so this alone does not meet our protected-address-space requirement.
- [Native Image C API](https://www.graalvm.org/latest/reference-manual/native-image/native-code-interoperability/C-API/)
  and [Graal sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/):
  separate heaps require a real isolate backend and correctly supported language
  configuration, not a build-profile name.
- [Unix descriptor passing](https://man7.org/linux/man-pages/man7/unix.7.html):
  SCM_RIGHTS references the same open file description; descriptor integers and
  application ownership must be managed independently.
- [Apple affinity API](https://developer.apple.com/library/archive/releasenotes/Performance/RN-AffinityAPI/index.html):
  documented cache-affinity hints are not explicit processor binding.

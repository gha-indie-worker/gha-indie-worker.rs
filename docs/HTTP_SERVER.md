# Native HTTP server capabilities

`http.listen(host, port, capacity, body_limit_bytes, deadline_ms)` creates an
HTTP/1.1 server under the supervisor's scoped NET permission. Port zero selects
an ephemeral port. The native backend uses `jdk.httpserver`; Java socket,
exchange, executor, and stream objects are never available to guest code.

## Ownership and dispatch

`server.accept()` returns `Future<HttpExchange>` to the supervisor. Register a
source actor with `http.handler("ActorType")` in the actor's declaring source
unit. `handler.dispatch(&mut exchange)` creates a fresh actor and moves the
exchange to it. Handler declarations must be shared or isolated, not untrusted.
The runtime checks the kind against the code image when initializing the actor.

The actor receives `ActorMail<String>` and calls `http.claim(mail.value)` once.
The token is bound to that actor ID and runtime, so possessing a token is
insufficient to claim it. Sender wrappers carry an old generation after the
move and cannot inspect, read, write, or transfer the exchange again. The
runtime also checks every data/I/O operation's actor ownership domain. The
can_respond predicate instead returns false for a stale wrapper. There is no
copy or generic mailbox transport path for HttpExchange, HttpHandler, or
HttpServer. Guest code cannot manufacture these opaque handles.

For lower-level supervisors, `exchange.move_to(actor_ref)` exposes the same
single-use transfer; the caller sends the returned String token. Both move
and claim reject stale owners, and failed dispatch cancels the destination
and closes the exchange. Actor startup failure and termination revoke any
unclaimed token and unfinished exchange.

The socket and HTTP framing state remain in the transport. An isolated actor
exclusively owns **guest authority** over one exchange; this is not a physical
transfer of a JVM object graph, OS FD table, Graal process, or connection into
an isolated heap. Private actor-local objects remain confined. Shared actors
use the same synchronized native exchange state and single-writer rule; this
API does not grant a second actor concurrent access to a request.

## Exchange API

| API | Result / purpose |
| --- | --- |
| `method`, `target`, `request_id` | Immutable request metadata |
| `path_size`, `path_at(i)` | Segments split before one strict percent decode |
| `header(name)`, `query(name)`, `cookie(name)` | First value, or empty String when absent |
| `param(name)`, `param_size(name)`, `param_at(name, i)` | Route captures copied as immutable strings |
| `operation_id`, `pipeline_id`, `contract_id` | Route metadata |
| `set_route(operation, pipeline, contract)`, `add_param(name, value)` | Supervisor-only, before transfer |
| `body_text()` | Future<String>, strict UTF-8, single consumption |
| `read_chunk(maximum)` | Future<Array<int>>, bytes 0..255, maximum 1..65536; empty at EOF |
| `set_header(name, value)` | Before headers commit; framing headers are transport-owned |
| `respond(status, content_type, body)` | Future<void>, one buffered UTF-8 response |
| `respond_bytes(status, content_type, bytes)` | Future<void>, one buffered byte response |
| `start(status, content_type)` | Future<void>, start chunked response |
| `write(text)`, `write_bytes(bytes)` | Future<void>, bounded chunk, flush with backpressure |
| `finish()` | Future<void>, complete streaming response |
| `can_respond` | False for moved/closed/committed/busy handles; used for error mapping |
| `abort()` | Cancel the request actor and close/error the exchange |
| `actor_startup_ns`, `move_to_claim_ns`, `server_timing` | Optional timings; overlapping intervals include scheduler delay |

Choose body_text or read_chunk; they cannot be mixed. The total streamed body
still obeys body_limit_bytes. Text and binary response chunks are bounded, and
only one I/O operation may be pending on an exchange. Await each operation
before starting the next. Headers reject control characters and invalid names.
HEAD, 204, 205, and 304 suppress response bodies. Status is limited to 200..599;
informational responses and protocol upgrades are not exposed.

I/O runs on virtual I/O workers, never on an actor or root scheduler carrier.
Source actors may await I/O while retaining their runtime-confined self lease;
ordinary user borrows and mutex guards still cannot cross await. Source helpers
returning Future<T> preserve one future layer even when conservative imported
call lowering introduces an internal continuation task.

## Lifecycle and limits

A request has a deadline from admission through actor termination. Capacity
counts both transport completion and actor finalization: forgetting self.end
cannot accumulate unlimited actors after sending a response. Finished actors
and exchanges release capacity exactly once. Exhausted capacity gets 503.
Unfinished actor termination gets 500 before headers, or connection closure
after headers. A deadline gets 504 when no I/O is in progress; otherwise it
closes the transport and cancels the actor. Partial responses are never retried.

`server.shutdown(grace_ms)` stops admission, gives the transport a bounded drain
period (rounded up to seconds by the backend), cancels remaining actors, and
settles after exchange/actor cleanup. Actor cancellation uses the existing
cooperative runtime semantics. `accepted`, `rejected`, `completed`, and `active`
are supervisor-only server counters. OresContext closes its HTTP servers before
tearing down actor/async runtimes.

Before the first JDK HTTP server initializes, the backend supplies JVM-wide
defaults: 1024 connections, 100 headers, 32768 header bytes, 15s request time,
30s response time, a one-byte unread-body drain, and TCP_NODELAY. Explicit JVM
properties override them. These protect parser/connection work before Spin's
per-listener admission check. Embedders that initialize another JDK HTTP server
first must set the properties before that initialization; the backend cannot
retroactively reconfigure JDK static limits. See NativeHttp.configureTransportDefaults.

This backend supports origin-form targets, strict UTF-8 paths, keep-alive,
chunked transfer, and streaming. It rejects absolute/network-path/fragment
request targets when they reach its handler. TLS termination, HTTP/2,
WebSockets, multipart parsing, JSON codecs, compression, and signed cookies
remain separate adapters/libraries. This is not a hostile-code sandbox.

## Native telemetry observation

Capture `exchange.completion: Future<int>` before dispatch/move. It resolves once
transport closure **and actor finalization** release admission, with the HTTP
status, or 0 when a response was interrupted/closed without a complete status
outcome. The future carries no exchange or socket authority and remains usable
after the observer loses exchange ownership. Getting it from a moved/closed
exchange is rejected. Awaiting it inside the owning handler before `self.end()`
would wait for that handler itself; capture/await it in the supervisor instead.
`exchange.admitted_ns` is the monotonic admission timestamp, readable only by the
current owner while open. Transport rejection before admission has no exchange
and is accounted for by `server.rejected`, not by these futures.

`process.monotonic_ns()` returns a process-local monotonic nanosecond clock for
intervals; `process.unix_ms()` returns epoch milliseconds for timestamps;
`process.unique_id()` returns a random UUID. They expose no host object, network,
filesystem or process metadata and are available to shared and isolated actors.
Monotonic timestamps are not portable across processes or restarts. Clock calls
are observations, not pure functions. String literals accept four-hex-digit
Unicode escapes, allowing native serializers to cover JSON control characters.

## Protected actor backend work

The planned native socket and request-scoped endpoint transfer paths, including
serialized-only untrusted handlers, are described in
[Actor physical boundaries](runtime/actor-physical-boundaries.md). The standalone
native transfer tests do not change the JDK transport ownership model above.

# Runtime boundary hardening

This audit covers lexical borrow suspension, secondary host-resource reclamation,
and failure propagation through source functions, methods, scheduler continuations,
actor completion, and HTTP I/O. JVM GC still manages guest Java objects;
`RuntimeGarbageCollector` manages registered host-resource cleanup hooks. It is not
an independent tracing heap collector and cannot repair an invalid borrow.

## Changes and invariants

- `rt borrow` is accepted at borrowed argument positions, including imported
  methods. `rt borrow mut` and `borrow mut T` use the existing exclusive-borrow
  checks. Compatibility parser aliases remain for existing compiler fixtures.
- Parentheses group expressions. Explicit `tuple (...)` constructs a product,
  including zero/singleton products. Immediate tuple unpacking creates lexical
  borrow leases; storing borrowed references in an owned tuple still fails closed.
- `rt cooperate` and `await` apply the same live-borrow and mutex checks. The
  actor's own mailbox lease remains valid across its continuation; aliases do not.
- Empty retired actor domains are not retained. Invalid cleanup registrations do
  not allocate domain entries. Successful cleanup drops its closure; closed
  collectors cannot be repopulated by late retry failures. Cancelled periodic
  tasks are removed from the timer queue promptly.
- Cleanup slots still serialize concurrent attempts. Failed live registrations
  remain retryable, and shutdown remains best-effort for failing host hooks.
- Uncaught source failures gain at most 64 suppressed source-boundary breadcrumbs.
  These contain only code-unit/callable strings, never environments, receivers,
  argument arrays, or actor heaps. Original exception identity, message, cause and
  cancellation type remain intact. Resumed failures identify the resumed frame.
- Panics allow these diagnostics while remaining non-trappable. HTTP I/O failures
  add an opaque request-ID breadcrumb; fatal JVM/thread/linkage errors are settled
  into the operation future and rethrown on the worker. HTTP clients still receive
  generic error responses, not internal traces.

## Validation and limits

Regression tests cover grouped and singleton-unpacked borrows, mutation rejection,
cooperative suspension, exclusive borrows, empty-domain cleanup, late failures
following close, cleanup-closure release, nested asynchronous failures, shared and
isolated actor failure propagation, and bounded diagnostic storage. Existing GC
race/model tests, scheduler waiter-detachment tests, actor transport tests, and
HTTP transfer/lifecycle tests remain part of the full suite.

The diagnostic records are bounded propagation breadcrumbs, not reconstructed
source-line stack frames. Source locations are currently code-unit paths and
callable names; optimized tail calls may elide callers. A failure delivered to
multiple consumers accumulates their boundary breadcrumbs under synchronization.
This does not add heap-root verification for every interop extension or a proof
of GC correctness. Cleanup closures must still avoid strongly capturing their
weakly tracked owners. HTTP handlers that are converted to generic 500 responses
do not expose their actor exception to clients.

# Typed exceptional-effect admission contract

**Status: reserved and fail-closed, NOT executable source-level feature parity.**

Both production Oreslang lexers reserve `throw`, `raise`, `panic` and
`recover`. Java/GraalVM has existing `defer`, `try/catch/finally` and
synchronous `trap` infrastructure, but these four new exceptional effects
must not be accepted as ordinary identifiers or callable expressions.
LLVM also rejects unsupported exception syntax before emitting LLVM IR.

## Future source effects (not yet admitted)

- `throw e`: typed recoverable `Throw<E>`; only typed matching catch or trap
  may consume the effect.
- `raise e`: explicit escalation, not caught by ordinary catch/trap.
- `panic e`: invariant failure, not caught by ordinary catch/trap.
- `recover`: capability-checked supervisor recovery; cannot recover a VM-fatal
  condition, forge a notice, or silently erase diagnostics.

## First executable milestone

A shared source fixture with `defer`, `throw` and a matching catch must
compile with both production frontends and run through GraalVM and native
LLVM/Clang. Compare return value, cleanup order, failure category and
suppressed diagnostics. Reject suspension/borrow escape until continuation
frame ownership and cancellation cleanup have executable proofs.

Do not mark parity completed for token reservation, AST parsing alone, policy
helpers, or native C++ tests. Actual guest code must be executed on both
production backends and checked against the same source Git blob.

Tracks LLVM issue #143 and Java/LLVM existing supervision draft work.

# Oreslang CLI exit statuses

The static root entrypoint supports `pub routine main(): void` (success = 0) and
`pub routine main(): int` (return 0–255). `async main` completion is awaited by
the root scheduler; a completed integer is likewise the exit status.
The linked CLI runner rejects other completed return values. Direct Polyglot
evaluation retains its existing value-returning entrypoint semantics; the
general type checker does not impose the CLI exit-status contract on embedding.

Use `std.process.exit(code)` (alias `process.exit(code)`) for early termination;
the argument is statically checked as `int` and validated at runtime in 0–255.
This is a runtime control signal, not a call from guest code to `System.exit`.
The standalone Java CLI launcher converts a nonzero status into the operating
system process exit code. Embedded users should use
`LinkedProgramRunner.runWithExitStatus(...)` and decide what to do with it.

The PROCESS_EXIT capability is required at compile time and at runtime.
This capability is granted in the developer/standalone profile, not in the
untrusted actor profile. Actor calls to process.exit are rejected so an actor
cannot kill its supervisor, another tenant, or the JVM.

A normal `return` from void main is status 0. Throwing an unhandled
exception does not grant the guest an arbitrary status; ordinary CLI failure
propagation remains separate. The 0–255 range is chosen for portable Unix-like
shell interoperability.

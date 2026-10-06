# Native Oreslang testing

Oreslang's user-facing test framework is implemented in Oreslang itself at
`stdlib/testing.ores`. It is not a Java/JUnit wrapper.

The Java/Truffle repository still uses host-side tests to verify the compiler and
runtime implementation, but application/library tests can be ordinary `.ores`
programs using the native framework.

## Design goals

The first native runner takes the parts of modern Go, Rust, Node.js, and Scala
test ecosystems that are useful at language-core level without pretending that
unfinished runtime services already exist.

Current surface:

- immutable test definitions instead of a process-global mutable registry;
- deterministic source order;
- hierarchical slash-separated names such as `math/mean/empty`;
- one exact hierarchical-name filter through `TestOptions.only` (`""` runs all);
- explicit skips with reasons;
- fail-fast as an option, not a semantic default;
- stable human-readable output;
- stable line-oriented machine output (`ORES_TEST|...`) for CI/tooling;
- reusable assertion helpers;
- deterministic table/property checks with caller-owned cases;
- aggregate `RunSummary`;
- non-zero process failure through `require_success`.

The machine protocol is deliberately simple and line-oriented. Native JSON
reporting should be added when the standard string/JSON layer can guarantee
correct escaping rather than emitting almost-JSON.

## Why this shape

Modern Go testing emphasizes hierarchical subtests, filtering, explicit
parallel opt-in, fuzzing, and machine-readable output. Rust's built-in/Cargo
test flow emphasizes deterministic discovery, name filtering, ignored tests,
captured output, and separate human/machine diagnostics. Node's built-in test
runner emphasizes subtests, explicit concurrency/isolation, reporters, mocks,
snapshots, and coverage.

Oreslang adopts the portable semantic pieces now and leaves scheduler/filesystem
features gated on the native primitives that must enforce them correctly.

## Example

```ores
import class TestCase, TestSuite, TestOptions, RunSummary, TestOutcome from '../stdlib/testing';
import fnc expect_equal_int, run_suite, require_success from '../stdlib/testing';

fnc adds(): TestOutcome {
  return expect_equal_int(2 + 2, 4, "addition");
}

pub routine main(): void {
  val TestSuite suite = new TestSuite(
    "math",
    arr[new TestCase("adds", adds, false, "")]
  );
  val TestOptions options = new TestOptions("", false, false);
  val RunSummary summary = run_suite(&suite, &options);
  require_success(&summary);
  return;
}
```

## Follow-ups

The following should not be simulated in userland before their native
dependencies are available:

1. scheduler-backed parallel test execution and per-test isolation;
2. monotonic-clock timeouts and virtual-time testing;
3. filesystem-backed snapshots;
4. coverage collection;
5. corpus fuzzing with shrinking/minimization;
6. regex/glob filtering once the standard string/regex library is stable;
7. automatic project test-file discovery in the Rust Oreslang CLI.

Those additions should preserve the existing `TestCase` / `RunSummary`
semantics and machine event contract.

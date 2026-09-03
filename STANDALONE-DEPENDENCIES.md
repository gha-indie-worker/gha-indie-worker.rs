# Standalone dependency boundary

The root Rust service was extracted from an internal superproject whose manifest
used three `../../libs/...` path dependencies. Those paths do not exist in a
standalone checkout and must never be reconstructed through mutable branches,
unreviewed filesystem mounts, or credentials.

The standalone repository resolves the boundary through reviewed, repository-local
compatibility crates:

| Package | Standalone path | Authority boundary |
| --- | --- | --- |
| `dd-telemetry` | `vendor/dd-telemetry` | Identity trace layer only |
| `dd-nats-subject-defs` | `vendor/dd-nats-subject-defs` | Fixed subject constants only |
| `dd-runtime-config-client` | `vendor/dd-runtime-config-client` | Empty router and no-op registration |

These crates preserve only the APIs needed by the extracted server and grant no
new execution, deployment, configuration, network, or credential authority.
They were semantically salvaged from the independently green standalone package
in PR #5; the older feature implementation was not merged into the parity audit.

## Required invariants

A standalone change must:

1. keep all three paths inside `vendor/` and reject `git`, branch, tag, revision,
   absolute, or parent-directory dependency selectors;
2. preserve the exact NATS subject constants used by the server;
3. keep runtime-config registration inert and telemetry limited to an identity
   layer unless a separately reviewed replacement is introduced;
4. regenerate and review `Cargo.lock` whenever a manifest changes;
5. run locked metadata, tests, and warnings-denied Clippy on the exact PR head;
6. copy `vendor/` into the container build context and build with `--locked`; and
7. keep credentials, private keys, tokens, and decrypted configuration out of
   the repository, logs, fixtures, and artifacts.

This is a packaging boundary, not a claim that the compatibility crates replace
their richer internal counterparts in the source monorepo.

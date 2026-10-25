#!/usr/bin/env bash
# Compile the REAL build server; never synthesize stand-in workspace crates.
set -euo pipefail

service="$(cd "$(dirname "$0")/.." && pwd -P)"
if [ "$#" -gt 0 ]; then service="$(cd "$1" && pwd -P)"; fi
if [ ! -f "$service/Cargo.toml" ] || [ ! -f "$service/Cargo.lock" ]; then
  echo "PRODUCTION_BUILD_BLOCKED: Cargo.toml or Cargo.lock is missing in $service" >&2
  exit 42
fi

# Fail before cargo is invoked when the extracted service is missing its
# production path dependencies. A passing mock/shim harness is NOT evidence.
missing=0
for path in   "../../libs/telemetry-rs"   "../../libs/runtime-config-client-rs"   "../../libs/nats/subject-defs/generated/rust"; do
  if [ ! -f "$service/$path/Cargo.toml" ] || [ ! -f "$service/$path/src/lib.rs" ]; then
    echo "PRODUCTION_BUILD_BLOCKED: missing real Cargo crate: $service/$path" >&2
    missing=1
  fi
done
if [ "$missing" -ne 0 ]; then exit 42; fi

if ! command -v git >/dev/null || ! command -v cargo >/dev/null; then
  echo "PRODUCTION_BUILD_BLOCKED: git and cargo are both required" >&2
  exit 42
fi
if [ -n "$EXPECTED_SERVICE_TREE_SHA" ]; then
  gitroot="$(git -C "$service" rev-parse --show-toplevel)"
  if [ "$service" = "$gitroot" ]; then
    actual_tree="$(git -C "$service" rev-parse 'HEAD^{tree}')"
  else
    subpath="$(realpath --relative-to="$gitroot" "$service")"
    actual_tree="$(git -C "$gitroot" rev-parse "HEAD:$subpath")"
  fi
  if [ "$actual_tree" != "$EXPECTED_SERVICE_TREE_SHA" ]; then
    echo "PRODUCTION_BUILD_BLOCKED: worker source Git tree mismatch: $actual_tree" >&2
    exit 42
  fi
fi

# This script does not materialize crates, replace dependencies or dispatch.
# Run it only in an authorized workspace containing the real sibling libraries.
cargo fmt --manifest-path "$service/Cargo.toml" -- --check
cargo metadata --manifest-path "$service/Cargo.toml" --locked --format-version 1 >/dev/null
cargo build --manifest-path "$service/Cargo.toml" --locked --all-targets
cargo test --manifest-path "$service/Cargo.toml" --locked --all-targets
cargo clippy --manifest-path "$service/Cargo.toml" --locked --all-targets -- -D warnings
echo "PRODUCTION_FULL_BUILD_OK: real workspace crates, cargo build/test/clippy passed"

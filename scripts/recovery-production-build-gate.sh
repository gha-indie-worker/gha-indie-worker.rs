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
# Every crate (including generated NATS constants) must be a clean,
# committed Git tree with a caller-supplied, independently reviewed SHA.
verify_tree() {
  local dir="$1" expected="$2" label="$3" root scope actual dirty
  if [ -z "$expected" ]; then
    echo "PRODUCTION_BUILD_BLOCKED: expected Git tree SHA missing for $label" >&2
    exit 42
  fi
  dir="$(cd "$dir" && pwd -P)"
  if ! root="$(git -C "$dir" rev-parse --show-toplevel 2>/dev/null)"; then
    echo "PRODUCTION_BUILD_BLOCKED: $label is not in a Git checkout" >&2
    exit 42
  fi
  if [ "$root" = "$dir" ]; then
    scope="."
    actual="$(git -C "$root" rev-parse 'HEAD^{tree}')"
  else
    scope="$(git -C "$dir" rev-parse --show-prefix)"
    scope="$(printf "%s" "$scope" | sed 's:/$::')"
    if ! actual="$(git -C "$root" rev-parse "HEAD:$scope" 2>/dev/null)"; then
      echo "PRODUCTION_BUILD_BLOCKED: $label has no committed tree" >&2
      exit 42
    fi
  fi
  if [ "$actual" != "$expected" ]; then
    echo "PRODUCTION_BUILD_BLOCKED: $label Git tree differs: $actual" >&2
    exit 42
  fi
  dirty="$(git -C "$root" status --porcelain --untracked-files=all -- "$scope")"
  if [ -n "$dirty" ]; then
    echo "PRODUCTION_BUILD_BLOCKED: $label contains dirty or untracked source" >&2
    exit 42
  fi
}

verify_tree "$service" "${EXPECTED_SERVICE_TREE_SHA:-}" "dd-build-server"
verify_tree "$service/../../libs/telemetry-rs" "${EXPECTED_TELEMETRY_TREE_SHA:-}" "dd-telemetry"
verify_tree "$service/../../libs/runtime-config-client-rs" "${EXPECTED_RUNTIME_CONFIG_TREE_SHA:-}" "dd-runtime-config-client"
verify_tree "$service/../../libs/nats/subject-defs/generated/rust" "${EXPECTED_NATS_SUBJECT_TREE_SHA:-}" "dd-nats-subject-defs"

# This script does not materialize crates, replace dependencies or dispatch.
# Run it only in an authorized workspace containing the real sibling libraries.
cargo fmt --manifest-path "$service/Cargo.toml" -- --check
cargo metadata --manifest-path "$service/Cargo.toml" --locked --format-version 1 >/dev/null
cargo build --manifest-path "$service/Cargo.toml" --locked --all-targets
cargo test --manifest-path "$service/Cargo.toml" --locked --all-targets
cargo clippy --manifest-path "$service/Cargo.toml" --locked --all-targets -- -D warnings
echo "PRODUCTION_FULL_BUILD_OK: real workspace crates, cargo build/test/clippy passed"

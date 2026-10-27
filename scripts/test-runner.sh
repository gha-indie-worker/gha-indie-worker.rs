#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/ores-runner.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
tmp="$(cd "$tmp" && pwd -P)"
project="$tmp/project with spaces"
mkdir -p "$project/scripts" "$project/tests" "$tmp/bin" "$tmp/empty"
cp "$root/scripts/test.sh" "$project/scripts/test.sh"
cp "$root/tests/main.ores" "$project/tests/main.ores"
cat > "$tmp/bin/compiler" <<'COMPILER'
#!/bin/sh
set -eu
test "$PWD" = "$RUNNER_PROJECT"
test "$#" = 1
test "$1" = "$RUNNER_PROJECT/tests/main.ores"
printf 'compiler invoked\n' >> "$RUNNER_LOG"
printf 'native output\n'
exit "${RUNNER_STATUS:-0}"
COMPILER
chmod +x "$tmp/bin/compiler"
ln -s "$(command -v dirname)" "$tmp/bin/dirname"
ln -s "$(command -v dirname)" "$tmp/empty/dirname"
ln -s "$tmp/bin/compiler" "$tmp/bin/oreslang"
export RUNNER_PROJECT="$project" RUNNER_LOG="$tmp/invocations"
cd "$tmp"
actual="$(ORESLANG_BIN="$tmp/bin/compiler" /bin/sh "$project/scripts/test.sh")"
test "$actual" = "native output"
test "$(cat "$RUNNER_LOG")" = "compiler invoked"
: > "$RUNNER_LOG"
actual="$(PATH="$tmp/bin" ORESLANG_BIN= /bin/sh "$project/scripts/test.sh")"
test "$actual" = "native output"
test "$(cat "$RUNNER_LOG")" = "compiler invoked"
set +e
ORESLANG_BIN="$tmp/bin/compiler" RUNNER_STATUS=44 /bin/sh "$project/scripts/test.sh" > "$tmp/output" 2>&1
status=$?
set -e
test "$status" = 44
grep -qx "native output" "$tmp/output"
set +e
PATH="$tmp/empty" ORESLANG_BIN= /bin/sh "$project/scripts/test.sh" > "$tmp/output" 2>&1
status=$?
set -e
test "$status" = 2
grep -q 'compiler/runtime not found' "$tmp/output"
: > "$RUNNER_LOG"
if PATH="$tmp/bin" ORESLANG_BIN="$tmp/missing" /bin/sh "$project/scripts/test.sh" > "$tmp/output" 2>&1; then
  echo "invalid explicit compiler accepted" >&2
  exit 1
fi
test ! -s "$RUNNER_LOG" # An invalid explicit binary must not fall back to PATH.
echo "runner contracts: PASS"

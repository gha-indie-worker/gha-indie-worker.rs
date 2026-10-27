#!/usr/bin/env sh
set -eu

repo_root="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo_root"

if [ -n "${ORESLANG_BIN:-}" ]; then
  exec "$ORESLANG_BIN" "$repo_root/tests/main.ores"
fi

if command -v oreslang >/dev/null 2>&1; then
  exec oreslang "$repo_root/tests/main.ores"
fi

echo "oreslang compiler/runtime not found; set ORESLANG_BIN or install the oreslang CLI" >&2
exit 2

#!/usr/bin/env sh
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
  java_bin="$(command -v java || true)"
  if [ -z "$java_bin" ]; then
    echo "JAVA_HOME is not set and java is not on PATH" >&2
    exit 1
  fi
  if command -v realpath >/dev/null 2>&1; then java_bin="$(realpath "$java_bin")"; fi
  JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
fi

case "$(uname -s)" in
  Linux) jni_os="linux"; output="target/native/liborescore.so"; shared_flags="-shared" ;;
  Darwin) jni_os="darwin"; output="target/native/liborescore.dylib"; shared_flags="-dynamiclib" ;;
  *) echo "native Oreslang core currently supports Linux and macOS" >&2; exit 1 ;;
esac

mkdir -p target/native
"${CC:-cc}" -std=c11 -O2 -fPIC -pthread $shared_flags   -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/$jni_os"   src/main/c/orescore.c -o "$output"

echo "built $output"

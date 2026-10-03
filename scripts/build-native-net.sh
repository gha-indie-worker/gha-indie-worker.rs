#!/usr/bin/env sh
set -eu

if [ -z "${JAVA_HOME:-}" ]; then
  java_bin="$(command -v java || true)"
  if [ -z "$java_bin" ]; then
    echo "JAVA_HOME is not set and java is not on PATH" >&2
    exit 1
  fi
  if command -v realpath >/dev/null 2>&1; then
    java_bin="$(realpath "$java_bin")"
  fi
  JAVA_HOME="$(cd "$(dirname "$java_bin")/.." && pwd)"
fi

os="$(uname -s)"
case "$os" in
  Linux)
    jni_os="linux"
    output="target/native/liboresnet.so"
    shared_flags="-shared"
    ;;
  Darwin)
    jni_os="darwin"
    output="target/native/liboresnet.dylib"
    shared_flags="-dynamiclib"
    ;;
  *)
    echo "native oresnet build currently supports Linux and macOS; got $os" >&2
    exit 1
    ;;
esac

cc_bin="${CC:-cc}"
mkdir -p target/native

"$cc_bin" \
  -std=c11 \
  -D_DEFAULT_SOURCE \
  -O2 \
  -fPIC \
  $shared_flags \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/$jni_os" \
  src/main/c/oresnet.c \
  -o "$output"

echo "built $output"

#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
case "$(uname -s)" in Linux|Darwin) ;; *) echo 'Unix mailbox tests require Linux or macOS' >&2; exit 1;; esac
mkdir -p target/native
sanitizers=""
if [ "${ORES_NATIVE_SANITIZE:-0}" = 1 ]; then
  sanitizers="-fsanitize=address,undefined -fno-omit-frame-pointer"
fi
"${CC:-cc}" -std=c11 -O2 -g -Wall -Wextra -Werror -fstack-protector-strong \
  $sanitizers -Isrc/main/c src/main/c/oresmailbox.c src/test/c/oresmailbox_test.c \
  -o target/native/oresmailbox-test
target/native/oresmailbox-test

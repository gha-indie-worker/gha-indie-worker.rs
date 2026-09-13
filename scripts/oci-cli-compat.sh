#!/usr/bin/env bash
set -euo pipefail

# Trusted local adapter for gha-indie-worker's fixed profile runner argv.
# The Rust worker still owns/constructs the full command; this script does not
# eval caller text or introduce a new remote shell surface.
#
# Selection:
#   GHA_INDIE_CONTAINER_RUNTIME=nerdctl|docker|podman
# or auto-detect in that order.

requested="${GHA_INDIE_CONTAINER_RUNTIME:-auto}"

choose_engine() {
  if [ "$requested" != "auto" ]; then
    case "$requested" in
      nerdctl|docker|podman) ;;
      *)
        echo "unsupported GHA_INDIE_CONTAINER_RUNTIME: $requested" >&2
        return 2
        ;;
    esac
    command -v "$requested" >/dev/null 2>&1 || {
      echo "requested container runtime is not installed: $requested" >&2
      return 127
    }
    printf '%s\n' "$requested"
    return 0
  fi

  for candidate in nerdctl docker podman; do
    if command -v "$candidate" >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  echo "no supported container CLI found (tried nerdctl, docker, podman)" >&2
  return 127
}

engine="$(choose_engine)"
args=("$@")

# gha-indie-worker currently calls nerdctl as:
#   nerdctl -n <containerd-namespace> run ...
# Docker and Podman do not accept that containerd namespace prefix, while the
# remaining run-profile flags are intentionally kept to their shared subset.
if [ "$engine" != "nerdctl" ] && [ "${#args[@]}" -ge 2 ] && [ "${args[0]}" = "-n" ]; then
  args=("${args[@]:2}")
fi

exec "$engine" "${args[@]}"

#!/usr/bin/env bash
set -euo pipefail

: "${BUILD_SERVER_GITHUB_WEBHOOK_SECRET:?set BUILD_SERVER_GITHUB_WEBHOOK_SECRET in your encrypted local environment}"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required and must already be authenticated with admin:repo_hook permission" >&2
  exit 2
fi

webhook_url="${INDIEBUILD_WEBHOOK_URL:-https://ci-laptop.indiebuild.dev/webhooks/github}"

if [ "$#" -eq 0 ]; then
  set -- \
    ORESoftware/ores-compose \
    ORESoftware/ores-sw.js \
    ORESoftware/ores-mobile-bg-procs \
    ORESoftware/ores-desktop-bg-procs \
    gha-indie-worker/gha-indie-worker.rs
fi

for repo in "$@"; do
  hook_id="$(gh api "/repos/${repo}/hooks" --paginate --jq ".[] | select(.config.url == \"${webhook_url}\") | .id" | head -n 1 || true)"

  if [ -n "$hook_id" ]; then
    echo "updating webhook for ${repo} (id=${hook_id})"
    gh api --method PATCH "/repos/${repo}/hooks/${hook_id}" \
      -f name=web \
      -F active=true \
      -f "config[url]=${webhook_url}" \
      -f 'config[content_type]=json' \
      -f "config[secret]=${BUILD_SERVER_GITHUB_WEBHOOK_SECRET}" \
      -f 'config[insecure_ssl]=0' \
      -f 'events[]=push' \
      -f 'events[]=pull_request' >/dev/null
  else
    echo "creating webhook for ${repo}"
    hook_id="$(gh api --method POST "/repos/${repo}/hooks" \
      -f name=web \
      -F active=true \
      -f "config[url]=${webhook_url}" \
      -f 'config[content_type]=json' \
      -f "config[secret]=${BUILD_SERVER_GITHUB_WEBHOOK_SECRET}" \
      -f 'config[insecure_ssl]=0' \
      -f 'events[]=push' \
      -f 'events[]=pull_request' \
      --jq '.id')"
  fi

  if [ "${INDIEBUILD_SKIP_WEBHOOK_PING:-0}" != "1" ]; then
    echo "requesting GitHub ping for ${repo} (id=${hook_id})"
    gh api --method POST "/repos/${repo}/hooks/${hook_id}/pings" >/dev/null
    # GitHub records delivery asynchronously. A short delay makes the first
    # delivery summary useful without turning bootstrap into a health gate.
    sleep 1
    gh api "/repos/${repo}/hooks/${hook_id}/deliveries?per_page=1" \
      --jq 'if length == 0 then "  ping delivery: pending" else .[0] | "  delivery id=\(.id) event=\(.event) status=\(.status_code // 0) redelivery=\(.redelivery)" end' \
      || true
  fi

done

echo "configured GitHub push + pull_request webhooks -> ${webhook_url}"

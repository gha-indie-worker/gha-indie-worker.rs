#!/usr/bin/env bash
set -euo pipefail

: "${BUILD_SERVER_GITHUB_WEBHOOK_SECRET:?set BUILD_SERVER_GITHUB_WEBHOOK_SECRET in your encrypted local environment}"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh CLI is required and must already be authenticated with admin:repo_hook permission" >&2
  exit 2
fi

webhook_url="${INDIEBUILD_WEBHOOK_URL:-https://ci-laptop.indiebuild.dev/webhooks/github}"

if [ "$#" -eq 0 ]; then
  set -- ORESoftware/ores-compose gha-indie-worker/gha-indie-worker.rs
fi

for repo in "$@"; do
  existing_id="$(gh api "/repos/${repo}/hooks" --paginate --jq ".[] | select(.config.url == \"${webhook_url}\") | .id" | head -n 1 || true)"

  if [ -n "$existing_id" ]; then
    echo "updating webhook for ${repo} (id=${existing_id})"
    gh api --method PATCH "/repos/${repo}/hooks/${existing_id}" \
      -f name=web \
      -F active=true \
      -f "config[url]=${webhook_url}" \
      -f 'config[content_type]=json' \
      -f "config[secret]=${BUILD_SERVER_GITHUB_WEBHOOK_SECRET}" \
      -f 'config[insecure_ssl]=0' \
      -f 'events[]=push' >/dev/null
  else
    echo "creating webhook for ${repo}"
    gh api --method POST "/repos/${repo}/hooks" \
      -f name=web \
      -F active=true \
      -f "config[url]=${webhook_url}" \
      -f 'config[content_type]=json' \
      -f "config[secret]=${BUILD_SERVER_GITHUB_WEBHOOK_SECRET}" \
      -f 'config[insecure_ssl]=0' \
      -f 'events[]=push' >/dev/null
  fi

done

echo "configured GitHub push webhooks -> ${webhook_url}"

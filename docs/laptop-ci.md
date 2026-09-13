# Laptop CI via Cloudflare Tunnel

This repository can run as an opportunistic CI worker on a developer laptop so long-running verification does not consume GitHub-hosted Actions minutes.

## Data path

```text
GitHub push webhook
  -> https://ci-laptop.indiebuild.dev/webhooks/github
  -> Cloudflare Tunnel
  -> 127.0.0.1:8100
  -> gha-indie-worker
  -> fixed profile container (rust-verify/node-verify/etc.)
```

The webhook path is the primary dispatcher because it does not need a GitHub Actions runner. The existing `X-Hub-Signature-256` verification remains the authentication boundary for GitHub webhook traffic. Build/operator endpoints retain the server authentication secret.

The laptop profile deliberately sets:

- deploy disabled;
- image push disabled;
- ECR login disabled;
- NATS intake disabled;
- fiducia coordination disabled;
- GitHub secret sync disabled;
- loopback-only HTTP bind;
- fixed, operator-reviewed profile commands only.

## Prerequisites

Install Rust/containerd+nerdctl (or the runtime needed by the selected profiles), `cloudflared`, and `ores-compose`.

The Cloudflare infrastructure for `ci-laptop.indiebuild.dev` lives in `gha-indie-worker-infra/cloudflare/laptop-tunnel`. Apply that Terraform root first, then place the resulting tunnel runtime token in your encrypted local environment as `CLOUDFLARE_TUNNEL_TOKEN`.

Also define two independent application secrets locally:

- `BUILD_SERVER_GITHUB_WEBHOOK_SECRET` — copied into the GitHub repository webhook configuration and used for GitHub HMAC verification;
- `BUILD_SERVER_AUTH_SECRET` — used by authenticated build/operator endpoints.

Do not commit any of these values.

## Start

From this repository:

```sh
ores-compose up --session laptop-ci
```

The root `.ores-compose.yaml` starts the Rust worker on `127.0.0.1:8100`, waits for `/healthz`, then starts `cloudflared` using `TUNNEL_TOKEN` from the environment. Both stdout/stderr streams are owned by the same ores-compose session and can be observed with:

```sh
ores-compose logs --session laptop-ci --follow
```

A second terminal can attach/control the same session using the ores-compose control socket.

## Configure repositories

The worker maps incoming repo pushes to fixed profiles with `examples/laptop/webhook-rules.json`. Add an explicit rule before enabling a repo; the server intentionally does not wildcard arbitrary repositories.

Then configure GitHub repository webhooks. With an authenticated `gh` CLI whose token can administer repository hooks:

```sh
bash scripts/bootstrap-laptop-webhooks.sh ORESoftware/ores-compose gha-indie-worker/gha-indie-worker.rs
```

The script is idempotent for the configured URL and subscribes only to `push`. A push to any branch can therefore run the matching profile even when GitHub Actions has no available hosted-runner minutes.

## Failure behavior

When the laptop is offline, Cloudflare has no active connector and GitHub webhook delivery fails/retries instead of silently falling back to an unrelated machine. GitHub exposes webhook delivery history and supports redelivery after the worker comes back online.

Treat the laptop as an opportunistic worker, not production infrastructure. Dedicated always-on workers can use the same API and profiles later.

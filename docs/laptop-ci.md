# Laptop CI via Cloudflare Tunnel

`gha-indie-worker` can run as an opportunistic external CI worker on a developer laptop so PR verification does not depend on GitHub-hosted Actions minutes.

## Primary PR path

```text
GitHub pull_request webhook
  -> https://ci-laptop.indiebuild.dev/webhooks/github
  -> Cloudflare Tunnel
  -> 127.0.0.1:8100
  -> gha-indie-worker
       |
       +-> validate PR payload + exact head SHA identity
       +-> GitHub API: read PR workflow YAML at that SHA
       +-> launch gha-indie-worker-infra/.ores-compose.yaml
       +-> interpret supported pull_request workflows
       +-> execute fixed sandbox verification profiles
       +-> GitHub API: pending -> success/failure commit status
```

The webhook path is the primary dispatcher because it does not need a GitHub Actions runner. GitHub webhook traffic remains authenticated by `X-Hub-Signature-256`. Build/operator endpoints retain their independent server-auth secret.

The laptop worker deliberately has deploy, image push, ECR login, NATS intake, fiducia coordination, and GitHub secret synchronization disabled. The PR workflow interpreter never executes PR-provided `run:` strings on the laptop host. It uses workflow YAML to select fixed, operator-reviewed sandbox profiles such as `rust-verify`, `node-verify`, `python-verify`, `flutter-verify`, Playwright, and Puppeteer.

Unsupported PR-workflow constructs fail closed. Workflows that do not subscribe to `pull_request` are ignored for PR gating. Workflow service containers should move into the organization topology rather than being launched a second time by the Actions interpreter.

## Canonical ores-compose ownership

The canonical org topology is not stored in this worker repository. It lives at:

```text
gha-indie-worker/gha-indie-worker-infra/.ores-compose.yaml
```

That file owns the local Postgres/Redis fallbacks, the four normal/admin server repos, the CI worker, Cloudflare Tunnel sidecar, session networking, and the Rust load-balancer topology. Application repositories retain their own Dockerfiles and entrypoints; `ores-compose discover` can find conventional `Dockerfile*` and `entrypoint.sh` launch surfaces.

The worker keeps automated infra checkouts beneath `BUILD_SERVER_ORG_WORKSPACE_ROOT` (default `~/.cache/gha-indie-worker/orgs`), never in a developer-owned source checkout.

## Prerequisites and secrets

Install Rust, `cloudflared`, `ores-compose`, and one supported local container CLI: nerdctl/containerd, Docker Desktop, or Podman. `scripts/oci-cli-compat.sh` auto-detects in that order and removes only nerdctl's `-n <namespace>` prefix when invoking Docker or Podman; the Rust worker still constructs the fixed profile command.

The Cloudflare infrastructure for `ci-laptop.indiebuild.dev` lives in `gha-indie-worker-infra/cloudflare/laptop-tunnel`. Apply that Terraform root first, then keep the tunnel runtime token in the encrypted local environment.

Required local secrets are:

- `CLOUDFLARE_TUNNEL_TOKEN` — remotely managed Cloudflare Tunnel connector token;
- `BUILD_SERVER_GITHUB_WEBHOOK_SECRET` — shared only with GitHub's repository webhook and used for HMAC verification;
- `BUILD_SERVER_AUTH_SECRET` — independent operator/build API authentication;
- `BUILD_SERVER_GIT_TOKEN` — narrowly scoped GitHub credential used for repository/workflow reads, private clone auth, PR-head checks, and commit-status writes. It is never forwarded to tested profile containers.

Do not commit any of these values.

## Start

Start the org topology from the infra repo:

```sh
cd ../gha-indie-worker-infra
ores-compose up --session laptop-ci
```

The canonical `.ores-compose.yaml` starts the worker on loopback, waits for `/healthz`, then starts `cloudflared`. Logs and second-terminal control remain part of the same ores-compose session:

```sh
ores-compose logs --session laptop-ci --follow
ores-compose attach --session laptop-ci
```

For a PR, the worker creates a distinct `pr-<number>-<sha>` session and tears it down after verification.

## Configure repository webhooks

With an authenticated `gh` CLI whose token can administer repository hooks:

```sh
bash scripts/bootstrap-laptop-webhooks.sh \
  ORESoftware/ores-compose \
  gha-indie-worker/gha-indie-worker.rs
```

The script is idempotent and subscribes to both `push` and `pull_request`. Push events remain available for the explicit `examples/laptop/webhook-rules.json` compatibility path. PR events use the new PR coordinator directly and do not require a per-repo profile rule; repository/profile allowlists still fail closed.

## CI correctness boundary

The external PR coordinator reads workflow YAML at the webhook's exact PR head SHA, rechecks that the PR head has not moved during the run, and publishes the classic GitHub status context `indiebuild/gha-indie-worker` on that SHA.

The shared build clone path still clones by branch today. Because the coordinator checks the PR head before each profile and again before publishing green, a branch advance causes the run to fail rather than incorrectly marking the old SHA successful. The remaining hardening item is detached exact-SHA clone/fetch in the shared build engine; track that under issue #56.

## Failure behavior

When the laptop is offline, Cloudflare has no active connector and GitHub webhook delivery fails instead of silently falling back to unrelated infrastructure. GitHub's webhook delivery history can be redelivered after the worker returns. A dedicated always-on worker can use the same API/session/profile model later.

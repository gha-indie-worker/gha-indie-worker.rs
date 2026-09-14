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
       +-> verify HMAC + delivery identity
       +-> validate PR payload + admitted head SHA identity
       +-> select fixed operator-reviewed repo/profile targets
       +-> launch gha-indie-worker-infra/.ores-compose.yaml
       +-> execute fixed sandbox verification profiles
       +-> GitHub Status API: indiebuild.dev/ci pending -> success/error
```

The webhook path is the primary dispatcher because it does not need a GitHub Actions runner. GitHub webhook traffic remains authenticated by `X-Hub-Signature-256`. Build/operator endpoints retain their independent server-auth secret.

The laptop worker deliberately has deploy, image push, ECR login, NATS intake, fiducia coordination, and GitHub secret synchronization disabled. The PR workflow interpreter never executes PR-provided `run:` strings on the laptop host. It uses only fixed, operator-reviewed sandbox profiles.

For repositories with complex matrices or multiple language roots, `BUILD_SERVER_PR_PROFILE_RULES` supplies an operator-owned repository -> profile/context mapping. PR code cannot alter this selection. Repositories without a rule may still use the fail-closed workflow interpreter when their `pull_request` workflow falls inside its supported subset.

Unsupported PR-workflow constructs fail closed. Workflows that do not subscribe to `pull_request` are ignored for PR gating. Workflow service containers should move into the organization topology rather than being launched a second time by the Actions interpreter.

## Canonical ores-compose ownership

The canonical org topology is not stored in this worker repository. It lives at:

```text
gha-indie-worker/gha-indie-worker-infra/.ores-compose.yaml
```

That file owns the local Postgres/Redis fallbacks, the four normal/admin server repos, the CI worker, Cloudflare Tunnel sidecar, session networking, and the operator-owned external-PR profile mapping. Application repositories retain their own Dockerfiles and entrypoints; `ores-compose discover` can find conventional `Dockerfile*` and `entrypoint.sh` launch surfaces.

The worker keeps automated infra checkouts beneath `BUILD_SERVER_ORG_WORKSPACE_ROOT` (default `~/.cache/gha-indie-worker/orgs`), never in a developer-owned source checkout.

The `ci-worker` and `cloudflare-tunnel` services belong to the `control` profile. The always-on laptop session enables that profile; nested PR sessions omit it so they launch only the application/infrastructure topology and cannot recursively create another worker/tunnel or collide on the control port.

## Prerequisites and secrets

Install Rust, `cloudflared`, `ores-compose`, `gh`, and one supported local container CLI: nerdctl/containerd, Docker Desktop, or Podman. `scripts/oci-cli-compat.sh` auto-detects in that order and removes only nerdctl's `-n <namespace>` prefix when invoking Docker or Podman; the Rust worker still constructs the fixed profile command.

The Cloudflare infrastructure for `ci-laptop.indiebuild.dev` lives in `gha-indie-worker-infra/cloudflare/laptop-tunnel`. Apply that Terraform root first, then keep the tunnel runtime token in the encrypted local environment.

Required local secrets are:

- `CLOUDFLARE_TUNNEL_TOKEN` — remotely managed Cloudflare Tunnel connector token;
- `BUILD_SERVER_GITHUB_WEBHOOK_SECRET` — shared only with GitHub's repository webhook and used for HMAC verification;
- `BUILD_SERVER_AUTH_SECRET` — independent operator/build API authentication;
- `BUILD_SERVER_GIT_TOKEN` — narrowly scoped GitHub credential used for repository/workflow reads, private clone auth, PR-head checks, and commit-status writes. It is never forwarded to tested profile containers.

Do not commit any of these values. The `gh` CLI used only for webhook bootstrap also needs permission to administer hooks on the target repositories.

Fixed Flutter/Dart profiles use a public digest-pinned GHCR image, so laptop PR verification does not require AWS ECR credentials.

## Start

Start the always-on control plane from the infra repo:

```sh
cd ../gha-indie-worker-infra
ores-compose up --session laptop-ci --profile control
```

The canonical `.ores-compose.yaml` starts the worker on loopback, waits for `/healthz`, then starts `cloudflared`. Logs and second-terminal control remain part of the same ores-compose session:

```sh
ores-compose logs --session laptop-ci --follow
ores-compose attach --session laptop-ci
```

For a PR, the worker creates a distinct `pr-<number>-<sha>` session without the `control` profile and tears it down after verification.

## Configure repository webhooks

With an authenticated `gh` CLI whose token can administer repository hooks, configure the runtime repositories with the same HMAC secret already present in the worker environment:

```sh
bash scripts/bootstrap-laptop-webhooks.sh \
  ORESoftware/ores-sw.js \
  ORESoftware/ores-mobile-bg-procs \
  ORESoftware/ores-desktop-bg-procs
```

Calling the script with no repository arguments also includes the bootstrap/default repositories. The script is idempotent: an existing hook for the same URL is updated rather than duplicated. It subscribes to both `push` and `pull_request`; PR events use the external coordinator directly.

The target URL is:

```text
https://ci-laptop.indiebuild.dev/webhooks/github
```

## Initial runtime profile policy

The companion infra repo centrally maps the runtime repositories as follows:

```text
ORESoftware/ores-sw.js
  .          -> node-source-verify
  .          -> rust-source-verify
  wasm       -> rust-wasm-verify

ORESoftware/ores-mobile-bg-procs
  .          -> node-source-verify       # TypeSpec / JSON Schema / TJSV
  .          -> rust-verify              # Cargo.lock required
  flutter    -> flutter-verify

ORESoftware/ores-desktop-bg-procs
  .          -> node-source-verify       # TypeSpec / JSON Schema / TJSV
  .          -> rust-verify              # Cargo.lock required
  clients/dart -> dart-verify
```

`rust-verify` is intentionally lockfile-strict. The mobile and desktop repositories therefore remain red until their generated root `Cargo.lock` files are committed; the external runner does not weaken that policy.

## GitHub-hosted Actions coexistence

Runtime repositories retain their heavyweight existing GitHub Actions suite for `main` pushes and manual `workflow_dispatch`. PR branches use `IndieBuild PR Handoff`, whose single job has `if: false`; this gives GitHub a visible PR workflow without allocating a hosted runner. Dispatch still comes from the signed repository webhook, not from the skipped workflow.

## CI correctness boundary

The external PR coordinator reads and records the webhook PR head SHA, rechecks that the PR head has not moved before profile execution and before reporting success, and publishes the classic GitHub status context `indiebuild.dev/ci` on that SHA.

The shared build clone path still clones by branch today. Because the coordinator checks the PR head before profiles and again before publishing green, a branch advance causes the run to fail rather than incorrectly marking the old SHA successful. This is still weaker than actually executing a detached immutable commit. Detached exact-SHA clone/fetch remains required under issue #56 before `indiebuild.dev/ci` should become a required branch-protection gate.

## Failure behavior

When the laptop is offline, Cloudflare has no active connector and GitHub webhook delivery fails instead of silently falling back to unrelated infrastructure. GitHub's webhook delivery history can be redelivered after the worker returns.

Issue #57 tracks the next architecture step: durable admission plus a pull/lease/fencing queue so an intermittently-online laptop can drain admitted CI work after reconnecting instead of depending on synchronous inbound delivery availability.

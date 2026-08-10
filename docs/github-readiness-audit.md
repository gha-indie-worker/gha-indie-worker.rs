# GitHub readiness audit

Unattended engineering runs must prove that GitHub is writable before they spend
time planning changes. The audit at `scripts/github_readiness_audit.py` checks:

- `gh` is installed and authenticated for `github.com`;
- the authenticated login is resolvable without printing credentials;
- each organization permits repository creation for that identity;
- each requested repository grants push-equivalent permission, which is needed
  for branches, commits, and pull requests;
- every sampled organization Project V2 board reports `viewerCanUpdate=true`.

It deliberately does **not** bypass branch protection or infer that a pull
request may be merged merely because the repository grants push permission.
Every merge still needs an exact head SHA, a conflict-free merge state, required
checks, and repository policy approval.

## Usage

```bash
python3 scripts/github_readiness_audit.py \
  --org gha-indie-worker \
  --repo gha-indie-worker/gha-indie-worker.rs
```

The command emits one JSON document and exits nonzero when a required capability
is missing or unproven. Tokens are never read from the environment or printed.
CLI stderr is bounded and credential-shaped values are redacted.

An organization with no existing Project V2 board cannot prove project update
access. Use `--allow-unproven-projects` only for bootstrap jobs that are not
expected to synchronize an existing board.

## Overnight priority gate

The intended overnight order is:

1. run this readiness audit;
2. write and test code;
3. push a branch and open a pull request;
4. merge only exact-head, conflict-free, policy-compliant green pull requests;
5. synchronize implementation evidence to Linear and the matching GitHub
   Project for each organization.

A readiness failure stops mutation work and records the exact organization or
repository blocker instead of falling back to chat-only artifacts.

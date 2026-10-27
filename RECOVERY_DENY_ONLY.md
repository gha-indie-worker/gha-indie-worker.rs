# DEN-3440: authenticated deny-only recovery observation

Staged on the admission adapter from worker PR #28. The production router
exposes `POST /recovery/observe` with the existing `x-server-auth` mechanism.

Body: `{"repo":"owner/name","run_id":123,"attempt":1,"head_sha":"<40 hex>"}`.

Requires a dedicated, read-scoped `BUILD_SERVER_GH_RECOVERY_READ_TOKEN` and
a connected Postgres database. The handler uses the sealed GitHub API observer,
which verifies the immutable run/attempt/SHA and complete bounded job pages.
Missing/contradictory provider evidence is denied. The endpoint never mints a
provider diagnosis, creates a clone, submits a job, or dispatches fallback.

Denial receipts are atomically inserted into the existing `webhook_deliveries`
table, relying on its `(provider, delivery_id)` unique constraint. The delivery
ID is a domain-separated SHA-256 of repository, run ID, attempt, and commit.
The audit entry includes classification/denial and bounded step/job counts.
Unlike ordinary webhook delivery handling, a persistence error fails closed.

**Known limitations and release gates**

- No automatic webhook-to-observer routing yet; a trusted caller must POST.
- No trusted provider billing/capacity diagnostic issuer yet.
- No claim lease, fencing, durable dispatch outbox, or crash reconciliation.
- No fallback execution is possible from this endpoint, by design.
- The separate CI compiles/runs the actual pure policy and checks production
  module syntax, but a full service build with external workspace dependencies
  and a real Postgres/API integration test remain mandatory.
- Keep source PR branch protection and independent review; never grant bypass
  or weaken required checks to merge this work.

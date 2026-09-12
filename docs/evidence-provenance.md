# Parity evidence provenance

A feature cannot be called `supported` merely because the parity catalog contains four trace paths. The checked-in evidence must prove all three bindings below.

## 1. Fixture binding

Each evidence entry records:

- `fixture`: the feature's exact positive or adversarial fixture path;
- `fixture_sha256`: SHA-256 of the checked-in fixture bytes;
- `workflow_sha`: the exact Git commit that supplied the workflow and runner implementation under test.

`tools/audit_gha_evidence_bindings.py` recomputes the fixture digest and rejects evidence that points at a different case, a missing file, a symlink, an oversized file, or modified bytes.

## 2. Source binding

Accepted evidence is one `gha-indie-worker.trace.v1` JSON object. Bare arrays and JSON Lines remain useful comparator inputs during development, but they are not sufficient provenance for a support claim.

The trace object repeats and must match the catalog's engine, case, platform, workflow SHA, fixture path/digest, and runner version. It also records:

- `normalization: gha-indie-worker.canonical-trace.v1`;
- `capture_tool_sha256`, binding the exact trace collector implementation;
- a source repository and positive attempt number;
- GitHub Actions run ID for a reference trace, or an opaque runner assignment ID for a clone trace.

Trace and fixture paths must stay inside the repository, must not traverse symlinks, and have explicit size limits. Trace digests are recomputed from the checked-in bytes.

## 3. Semantic binding

For each supported positive/adversarial case, GitHub Actions and clone evidence must cover the identical platform set. The audit loads both event sequences and runs the bounded, secret-safe comparator.

A status, conclusion, event, output, context, cleanup step, ordering, or event-count difference fails the support claim. Diagnostics show only event names, JSON-pointer paths, and SHA-256 fingerprints; they do not print differing values.

## Example shape

```json
{
  "engine": "github-actions",
  "case": "positive",
  "platform": "linux-x64",
  "workflow_sha": "0123456789abcdef0123456789abcdef01234567",
  "runner_version": "2.x.y",
  "fixture": "conformance/fixtures/workflows/example-positive.yml",
  "fixture_sha256": "sha256:...",
  "trace": "conformance/evidence/example-positive-github-linux-x64.json",
  "trace_sha256": "sha256:..."
}
```

The referenced trace object repeats those values and includes the source, capture-tool digest, normalization version, and non-empty `events` array.

## What this still does not prove

Checked-in metadata is an integrity and review boundary, not a cryptographic statement from GitHub. A production release gate should additionally verify the reference run through a dedicated low-privilege conformance repository, immutable artifact retention, and a trusted capture workflow. Until that exists, the catalog remains conservative and all currently unproven features remain `unverified`.

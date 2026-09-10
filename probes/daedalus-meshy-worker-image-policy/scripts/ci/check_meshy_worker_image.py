#!/usr/bin/env python3
"""Credential-free policy contract for Meshy worker OCI publication."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github/workflows/meshy-worker-image.yml"
DOCKERFILE_PATH = ROOT / "crates/meshy-worker/Dockerfile"
WORKER_MANIFEST_PATH = ROOT / "crates/meshy-worker/Cargo.toml"


def fail(message: str) -> None:
    raise SystemExit(f"Meshy worker image policy failed: {message}")


def require(text: str, tokens: list[str], subject: str) -> None:
    missing = [token for token in tokens if token not in text]
    if missing:
        fail(f"{subject} is missing required contracts: {', '.join(missing)}")


def main() -> None:
    for path in (WORKFLOW_PATH, DOCKERFILE_PATH, WORKER_MANIFEST_PATH):
        if not path.is_file():
            fail(f"missing {path.relative_to(ROOT)}")

    workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
    dockerfile = DOCKERFILE_PATH.read_text(encoding="utf-8")
    worker_manifest = WORKER_MANIFEST_PATH.read_text(encoding="utf-8")

    require(
        workflow,
        [
            "pull_request:",
            "push:",
            "workflow_dispatch:",
            "packages: write",
            "persist-credentials: false",
            "submodules: false",
            "ghcr.io/daedalus-fab/meshy-job-worker",
            "file: crates/meshy-worker/Dockerfile",
            "sbom: true",
            "provenance: mode=max",
            "10001:10001",
            "/usr/local/bin/meshy-job-worker",
            "steps.publish.outputs.digest",
            "steps.exact.outputs.image_ref",
            "docker pull \"${PUBLISHED_IMAGE}\"",
            "org.opencontainers.image.revision",
            "exact_digest_pulled: true",
            "exact_runtime_contract_verified: true",
            'exact_digest_vulnerability_scan: "passed"',
            "provider_credentials_used: false",
            "live_meshy_task_submitted: false",
            "fabrication_release_authorized: false",
            "retention-days: 90",
        ],
        str(WORKFLOW_PATH.relative_to(ROOT)),
    )

    ordered_steps = [
        "Build local candidate from the compact resolver artifact",
        "Verify local non-root runtime and source contract",
        "Scan local pull-request candidate",
        "Log in to GitHub Container Registry",
        "Publish digest-addressable image with SBOM and provenance",
        "Resolve exact published image",
        "Pull exact published digest",
        "Verify exact published runtime and source contract",
        "Scan exact published digest",
        "Write machine-readable exact-digest evidence",
        "Upload machine-readable exact-digest evidence",
        "Record exact published digest",
    ]
    positions = [workflow.find(step) for step in ordered_steps]
    if any(position < 0 for position in positions) or positions != sorted(positions):
        fail("trusted publication steps must appear in build/scan/publish/pull/inspect/scan/evidence order")

    trusted_condition = (
        "(github.event_name == 'push' || github.event_name == 'workflow_dispatch') "
        "&& github.ref == 'refs/heads/main'"
    )
    if workflow.count(trusted_condition) != 9:
        fail("all nine registry, exact-digest, and evidence steps must be restricted to trusted main")

    secret_references = set(re.findall(r"secrets\.([A-Z][A-Z0-9_]*)", workflow))
    if secret_references != {"GITHUB_TOKEN"}:
        fail(
            "the publication workflow secret surface must be exactly GITHUB_TOKEN; "
            f"found {sorted(secret_references)}"
        )

    forbidden = [
        "pull_request_target",
        "persist-credentials: true",
        "latest=true",
        "type=raw,value=latest",
        "MESHY_API_KEY",
        "MESHY_API_BASE_URL",
        "DAEDALUS_R2_ACCESS_KEY_ID",
        "DAEDALUS_R2_SECRET_ACCESS_KEY",
        "CLOUDFLARE_API_TOKEN",
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
        "GH_PAT",
        "K8S_SUBMODULE_APP_PRIVATE_KEY",
        "kubectl ",
        "helm ",
        "argocd ",
        "wrangler ",
        "docker run",
    ]
    present = [token for token in forbidden if token in workflow]
    if present:
        fail("workflow contains forbidden credentials, mutable release tags, or live operations: " + ", ".join(present))

    action_refs = re.findall(r"uses:\s*[^\s]+@([^\s#]+)", workflow)
    if not action_refs or any(not re.fullmatch(r"[0-9a-f]{40}", ref) for ref in action_refs):
        fail("every third-party GitHub Action must be pinned to an exact 40-character commit SHA")

    if workflow.count("uses: docker/build-push-action@") != 2:
        fail("the workflow must build one local candidate and one separately published image")
    if workflow.count("uses: aquasecurity/trivy-action@") != 2:
        fail("the local candidate and exact published digest must each be vulnerability-scanned")

    require(
        dockerfile,
        [
            "COPY crates/meshy-client crates/meshy-client",
            "COPY crates/meshy-job crates/meshy-job",
            "COPY crates/meshy-r2-archive crates/meshy-r2-archive",
            "COPY crates/meshy-worker crates/meshy-worker",
            "RUN ./crates/meshy-worker/prepare-lock.sh",
            "cargo build \\",
            "--locked",
            "--manifest-path crates/meshy-worker/Cargo.toml",
            "USER 10001:10001",
            'ENTRYPOINT ["/usr/local/bin/meshy-job-worker"]',
        ],
        str(DOCKERFILE_PATH.relative_to(ROOT)),
    )
    require(
        worker_manifest,
        [
            'name = "dd-meshy-worker"',
            'dd-meshy-job = { path = "../meshy-job" }',
            'dd-meshy-r2-archive = { path = "../meshy-r2-archive" }',
        ],
        str(WORKER_MANIFEST_PATH.relative_to(ROOT)),
    )

    for relative in (
        "crates/meshy-worker/Cargo.lock.gz",
        "crates/meshy-worker/Cargo.lock.gz.sha256",
        "crates/meshy-worker/Cargo.lock.sha256",
        "crates/meshy-worker/prepare-lock.sh",
    ):
        if not (ROOT / relative).is_file():
            fail(f"missing compact resolver artifact component {relative}")

    print(
        "Meshy worker publication contract is valid: pull-request builds are credential-free; "
        "trusted main publication verifies the exact GHCR digest before emitting evidence"
    )


if __name__ == "__main__":
    main()

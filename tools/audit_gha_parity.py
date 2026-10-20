#!/usr/bin/env python3
"""Fail-closed structural and evidence audit for GitHub Actions parity claims."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
from typing import Any

CATALOG_SCHEMA = 'gha-indie-worker.parity-catalog.v1'
DEFAULT_CATALOG_PATH = 'conformance/gha-parity-catalog.json'
MAX_CATALOG_BYTES = 2_097_152
MAX_FIXTURE_BYTES = 1_048_576
MAX_TRACE_BYTES = 16_777_216
ALLOWED_STATUSES = {'unverified', 'partial', 'supported', 'blocked'}
ALLOWED_RISKS = {'low', 'medium', 'high', 'critical'}
ALLOWED_ENGINES = {'github-actions', 'gha-indie-worker'}
ALLOWED_CASES = {'positive', 'adversarial'}
SLUG = re.compile(r'^[a-z0-9]+(?:[._-][a-z0-9]+)*$')
LINEAR_ISSUE = re.compile(r'^DEN-[1-9][0-9]*$')
EXACT_SHA = re.compile(r'^(?:[0-9a-f]{40}|[0-9a-f]{64})$')
SHA256 = re.compile(r'^sha256:[0-9a-f]{64}$')
TRUE_VALUES = {'1', 'true', 'yes', 'on'}

MANDATORY_FEATURE_IDS = frozenset({
    'workflow.yaml-parsing',
    'workflow.event-filters',
    'workflow.reusable-workflows',
    'expression.coercion',
    'expression.functions',
    'context.github',
    'context.env-vars-inputs-secrets',
    'context.steps-needs-job-runner',
    'strategy.matrix',
    'job.dependencies-and-if',
    'job.failure-cancellation-timeout',
    'job.concurrency',
    'step.shells-working-directory',
    'step.file-commands',
    'step.annotations-and-groups',
    'action.javascript',
    'action.composite',
    'action.docker',
    'action.remote-resolution',
    'runner.labels-groups-routing',
    'runner.os-architecture',
    'container.job-container',
    'container.service-containers',
    'data.artifacts',
    'data.caches',
    'security.secret-masking',
    'security.token-permissions',
    'security.oidc',
    'security.archive-extraction',
    'security.workspace-isolation',
    'environment.protection',
    'observability.logs-timeline-results',
    'protocol.runner-service',
    'protocol.retry-idempotency',
})


@dataclass(frozen=True, order=True)
class Finding:
    code: str
    path: str
    message: str

    def render(self) -> str:
        where = f' [{self.path}]' if self.path else ''
        return f'{self.code}{where}: {self.message}'


def _inside(root: Path, candidate: Path) -> bool:
    try:
        candidate.relative_to(root)
        return True
    except ValueError:
        return False


def _safe_relative_path(value: object) -> PurePosixPath | None:
    if not isinstance(value, str) or not value or any(ord(character) < 32 for character in value):
        return None
    path = PurePosixPath(value)
    if path.is_absolute() or '..' in path.parts or '.' in path.parts:
        return None
    return path


def _repo_file(
    root: Path,
    value: object,
    *,
    max_bytes: int,
    required_prefix: str | None = None,
) -> tuple[Path | None, str | None]:
    rel = _safe_relative_path(value)
    if rel is None:
        return None, 'must be a safe repository-relative path'
    if required_prefix and rel.parts[: len(PurePosixPath(required_prefix).parts)] != PurePosixPath(required_prefix).parts:
        return None, f'must be stored beneath {required_prefix}'

    source = root.joinpath(*rel.parts)
    cursor = root
    for part in rel.parts:
        cursor /= part
        if cursor.is_symlink():
            return None, 'must not traverse a symlink'
    resolved = source.resolve(strict=False)
    if not _inside(root, resolved):
        return None, 'must remain inside the audited repository'
    if not resolved.is_file():
        return None, 'does not exist as a regular file'
    try:
        size = resolved.stat().st_size
    except OSError as error:
        return None, f'cannot be inspected: {error}'
    if size > max_bytes:
        return None, f'exceeds the {max_bytes}-byte limit'
    return resolved, None


def _load_catalog(root: Path, catalog_path: str) -> tuple[dict[str, Any] | None, list[Finding]]:
    path, error = _repo_file(root, catalog_path, max_bytes=MAX_CATALOG_BYTES)
    if error:
        return None, [Finding('PARITY-CATALOG-001', catalog_path, error)]
    assert path is not None
    try:
        with path.open('r', encoding='utf-8') as handle:
            document = json.load(handle)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error_value:
        return None, [Finding('PARITY-CATALOG-002', catalog_path, f'unable to parse JSON: {error_value}')]
    if not isinstance(document, dict):
        return None, [Finding('PARITY-CATALOG-003', catalog_path, 'catalog root must be a JSON object')]
    return document, []


def _audit_fixture(root: Path, feature_id: str, case_name: str, value: object) -> list[Finding]:
    if value is None:
        return []
    path_label = f'{feature_id}.{case_name}_fixture'
    path, error = _repo_file(root, value, max_bytes=MAX_FIXTURE_BYTES)
    if error:
        return [Finding('PARITY-FIXTURE-001', path_label, error)]
    assert path is not None
    if path.suffix.lower() not in {'.yml', '.yaml'}:
        return [Finding('PARITY-FIXTURE-002', path_label, 'workflow fixture must use .yml or .yaml')]
    return []


def _audit_evidence(
    root: Path,
    feature_id: str,
    evidence: object,
) -> tuple[list[Finding], set[tuple[str, str, str]]]:
    path_label = f'{feature_id}.evidence'
    if not isinstance(evidence, list):
        return [Finding('PARITY-EVIDENCE-001', path_label, 'evidence must be an array')], set()

    findings: list[Finding] = []
    tuples: set[tuple[str, str, str]] = set()
    identities: set[tuple[str, str, str, str]] = set()
    for index, item in enumerate(evidence, start=1):
        label = f'{path_label}[{index}]'
        if not isinstance(item, dict):
            findings.append(Finding('PARITY-EVIDENCE-002', label, 'evidence entry must be an object'))
            continue

        engine = item.get('engine')
        case_name = item.get('case')
        platform = item.get('platform')
        workflow_sha = item.get('workflow_sha')
        trace_value = item.get('trace')
        trace_sha = item.get('trace_sha256')
        runner_version = item.get('runner_version')

        if engine not in ALLOWED_ENGINES:
            findings.append(Finding('PARITY-EVIDENCE-003', label, 'engine must be github-actions or gha-indie-worker'))
        if case_name not in ALLOWED_CASES:
            findings.append(Finding('PARITY-EVIDENCE-004', label, 'case must be positive or adversarial'))
        if not isinstance(platform, str) or not SLUG.fullmatch(platform):
            findings.append(Finding('PARITY-EVIDENCE-005', label, 'platform must be a lowercase slug'))
        if not isinstance(workflow_sha, str) or not EXACT_SHA.fullmatch(workflow_sha):
            findings.append(Finding('PARITY-EVIDENCE-006', label, 'workflow_sha must be exact lowercase 40- or 64-hex'))
        if not isinstance(runner_version, str) or not runner_version.strip() or len(runner_version) > 200:
            findings.append(Finding('PARITY-EVIDENCE-007', label, 'runner_version must be a bounded non-empty string'))
        if not isinstance(trace_sha, str) or not SHA256.fullmatch(trace_sha):
            findings.append(Finding('PARITY-EVIDENCE-008', label, 'trace_sha256 must be lowercase sha256:<64 hex>'))

        trace_path, trace_error = _repo_file(
            root,
            trace_value,
            max_bytes=MAX_TRACE_BYTES,
            required_prefix='conformance/evidence',
        )
        if trace_error:
            findings.append(Finding('PARITY-EVIDENCE-009', label, trace_error))
        elif trace_path is not None and isinstance(trace_sha, str) and SHA256.fullmatch(trace_sha):
            try:
                actual = f'sha256:{hashlib.sha256(trace_path.read_bytes()).hexdigest()}'
            except OSError as error:
                findings.append(Finding('PARITY-EVIDENCE-010', label, f'unable to hash trace: {error}'))
            else:
                if actual != trace_sha:
                    findings.append(Finding('PARITY-EVIDENCE-011', label, 'trace digest does not match checked-in bytes'))

        if (
            engine in ALLOWED_ENGINES
            and case_name in ALLOWED_CASES
            and isinstance(platform, str)
            and SLUG.fullmatch(platform)
        ):
            tuples.add((engine, case_name, platform))
            trace_identity = trace_value if isinstance(trace_value, str) else ''
            identity = (engine, case_name, platform, trace_identity)
            if identity in identities:
                findings.append(Finding('PARITY-EVIDENCE-012', label, 'duplicate engine/case/platform/trace evidence'))
            identities.add(identity)

    return findings, tuples


def audit(
    root: Path,
    *,
    catalog_path: str = DEFAULT_CATALOG_PATH,
    require_full: bool = False,
) -> tuple[list[Finding], dict[str, int]]:
    root = root.resolve()
    catalog, findings = _load_catalog(root, catalog_path)
    counts = {status: 0 for status in sorted(ALLOWED_STATUSES)}
    if catalog is None:
        return findings, counts

    if catalog.get('schema') != CATALOG_SCHEMA:
        findings.append(Finding('PARITY-CATALOG-004', catalog_path, f'schema must be {CATALOG_SCHEMA}'))
    revision = catalog.get('catalog_revision')
    try:
        if not isinstance(revision, str):
            raise ValueError('not a string')
        date.fromisoformat(revision)
    except ValueError:
        findings.append(Finding('PARITY-CATALOG-005', catalog_path, 'catalog_revision must be an ISO-8601 date'))

    claims = catalog.get('claims')
    if not isinstance(claims, dict):
        findings.append(Finding('PARITY-CLAIM-001', catalog_path, 'claims must be an object'))
        claims = {}
    for key in ('full_parity', 'production_drop_in_replacement'):
        if not isinstance(claims.get(key), bool):
            findings.append(Finding('PARITY-CLAIM-002', f'claims.{key}', 'claim must be a boolean'))

    definitions = catalog.get('status_definitions')
    if not isinstance(definitions, dict) or set(definitions) != ALLOWED_STATUSES:
        findings.append(Finding('PARITY-CATALOG-006', 'status_definitions', 'must define exactly the four allowed statuses'))

    features = catalog.get('features')
    if not isinstance(features, list):
        findings.append(Finding('PARITY-FEATURE-001', 'features', 'features must be an array'))
        return sorted(set(findings)), counts

    seen: set[str] = set()
    feature_status: dict[str, tuple[str, bool]] = {}
    for index, feature in enumerate(features, start=1):
        label = f'features[{index}]'
        if not isinstance(feature, dict):
            findings.append(Finding('PARITY-FEATURE-002', label, 'feature must be an object'))
            continue

        feature_id = feature.get('id')
        if not isinstance(feature_id, str) or not SLUG.fullmatch(feature_id):
            findings.append(Finding('PARITY-FEATURE-003', label, 'id must be a lowercase dotted slug'))
            continue
        if feature_id in seen:
            findings.append(Finding('PARITY-FEATURE-004', feature_id, 'feature id is duplicated'))
            continue
        seen.add(feature_id)

        title = feature.get('title')
        if not isinstance(title, str) or not title.strip() or len(title) > 240:
            findings.append(Finding('PARITY-FEATURE-005', feature_id, 'title must be a bounded non-empty string'))
        category = feature.get('category')
        if not isinstance(category, str) or not SLUG.fullmatch(category):
            findings.append(Finding('PARITY-FEATURE-006', feature_id, 'category must be a lowercase slug'))
        risk = feature.get('risk')
        if risk not in ALLOWED_RISKS:
            findings.append(Finding('PARITY-FEATURE-007', feature_id, 'risk must be low, medium, high, or critical'))
        required = feature.get('required_for_full_parity')
        if not isinstance(required, bool):
            findings.append(Finding('PARITY-FEATURE-008', feature_id, 'required_for_full_parity must be boolean'))
            required = False
        status = feature.get('status')
        if status not in ALLOWED_STATUSES:
            findings.append(Finding('PARITY-FEATURE-009', feature_id, 'status is invalid'))
            status = 'unverified'
        counts[status] += 1
        feature_status[feature_id] = (status, required)

        tracking = feature.get('tracking')
        if not isinstance(tracking, str) or not LINEAR_ISSUE.fullmatch(tracking):
            findings.append(Finding('PARITY-FEATURE-010', feature_id, 'tracking must be a Linear issue identifier such as DEN-2586'))

        findings.extend(_audit_fixture(root, feature_id, 'positive', feature.get('positive_fixture')))
        findings.extend(_audit_fixture(root, feature_id, 'adversarial', feature.get('adversarial_fixture')))
        evidence_findings, evidence_tuples = _audit_evidence(root, feature_id, feature.get('evidence'))
        findings.extend(evidence_findings)
        evidence = feature.get('evidence') if isinstance(feature.get('evidence'), list) else []

        if status == 'unverified' and evidence:
            findings.append(Finding('PARITY-STATE-001', feature_id, 'unverified features must not carry accepted evidence'))
        if status == 'partial' and not evidence:
            findings.append(Finding('PARITY-STATE-002', feature_id, 'partial features require accepted evidence'))
        if status == 'blocked':
            blocker = feature.get('blocker')
            if not isinstance(blocker, str) or not blocker.strip():
                findings.append(Finding('PARITY-STATE-003', feature_id, 'blocked features require a non-empty blocker'))
        if status == 'supported':
            if feature.get('positive_fixture') is None or feature.get('adversarial_fixture') is None:
                findings.append(Finding('PARITY-STATE-004', feature_id, 'supported features require positive and adversarial fixtures'))
            for case_name in ALLOWED_CASES:
                reference_platforms = {
                    platform for engine, observed_case, platform in evidence_tuples
                    if engine == 'github-actions' and observed_case == case_name
                }
                clone_platforms = {
                    platform for engine, observed_case, platform in evidence_tuples
                    if engine == 'gha-indie-worker' and observed_case == case_name
                }
                if not reference_platforms.intersection(clone_platforms):
                    findings.append(Finding(
                        'PARITY-STATE-005',
                        feature_id,
                        f'supported status requires matched GitHub and clone evidence for the {case_name} case',
                    ))

    missing = sorted(MANDATORY_FEATURE_IDS - seen)
    for feature_id in missing:
        findings.append(Finding('PARITY-FEATURE-011', feature_id, 'mandatory parity feature is missing from the catalog'))

    required_not_supported = sorted(
        feature_id for feature_id, (status, required) in feature_status.items()
        if required and status != 'supported'
    )
    full_claim = claims.get('full_parity') is True
    drop_in_claim = claims.get('production_drop_in_replacement') is True
    if full_claim and (missing or required_not_supported):
        findings.append(Finding('PARITY-CLAIM-003', 'claims.full_parity', 'cannot be true until every mandatory required feature is supported'))
    if drop_in_claim and not full_claim:
        findings.append(Finding('PARITY-CLAIM-004', 'claims.production_drop_in_replacement', 'requires a valid full_parity claim'))
    if require_full and (not full_claim or missing or required_not_supported):
        findings.append(Finding('PARITY-CLAIM-005', 'claims.full_parity', 'full parity is required by this audit invocation but is not proven'))

    return sorted(set(findings)), counts


def main() -> int:
    root = Path(os.environ.get('GHA_PARITY_ROOT', Path(__file__).resolve().parents[1]))
    catalog_path = os.environ.get('GHA_PARITY_CATALOG', DEFAULT_CATALOG_PATH)
    require_full = os.environ.get('GHA_REQUIRE_FULL_PARITY', '').strip().lower() in TRUE_VALUES
    findings, counts = audit(root, catalog_path=catalog_path, require_full=require_full)
    if findings:
        print(f'GitHub Actions parity audit failed with {len(findings)} finding(s):', file=sys.stderr)
        for finding in findings:
            print(f'- {finding.render()}', file=sys.stderr)
        return 1
    rendered_counts = ', '.join(f'{status}={counts[status]}' for status in sorted(counts))
    print(f'GitHub Actions parity catalog passed structural audit ({rendered_counts})')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

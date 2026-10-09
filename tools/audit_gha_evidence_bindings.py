#!/usr/bin/env python3
"""Verify parity evidence provenance and paired trace equivalence.

The structural catalog audit proves that claims use recognized states and
checked-in evidence. This audit proves that each accepted evidence entry is
bound to the exact fixture bytes and that reference/clone traces for a
supported feature actually match after the bounded normalization implemented
by compare_gha_traces.py.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import sys
from typing import Any

TOOLS_DIR = Path(__file__).resolve().parent
COMPARE_PATH = TOOLS_DIR / 'compare_gha_traces.py'
SPEC = importlib.util.spec_from_file_location('compare_gha_traces', COMPARE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f'unable to load {COMPARE_PATH}')
COMPARE = importlib.util.module_from_spec(SPEC)
sys.modules.setdefault(SPEC.name, COMPARE)
SPEC.loader.exec_module(COMPARE)

CATALOG_PATH = 'conformance/gha-parity-catalog.json'
TRACE_SCHEMA = 'gha-indie-worker.trace.v1'
MAX_CATALOG_BYTES = 2_097_152
MAX_FIXTURE_BYTES = 1_048_576
MAX_TRACE_BYTES = 16_777_216
SHA256 = re.compile(r'^sha256:[0-9a-f]{64}$')
EXACT_SHA = re.compile(r'^(?:[0-9a-f]{40}|[0-9a-f]{64})$')
REPOSITORY = re.compile(r'^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')
OPAQUE_ID = re.compile(r'^[A-Za-z0-9][A-Za-z0-9_.:-]{7,255}$')
PLATFORM = re.compile(r'^[a-z0-9]+(?:[._-][a-z0-9]+)*$')
CASES = frozenset({'positive', 'adversarial'})
ENGINES = frozenset({'github-actions', 'gha-indie-worker'})


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


def _safe_relative(value: object) -> PurePosixPath | None:
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
    rel = _safe_relative(value)
    if rel is None:
        return None, 'must be a safe repository-relative path'
    if required_prefix:
        prefix = PurePosixPath(required_prefix)
        if rel.parts[: len(prefix.parts)] != prefix.parts:
            return None, f'must be stored beneath {required_prefix}'

    cursor = root
    for part in rel.parts:
        cursor /= part
        if cursor.is_symlink():
            return None, 'must not traverse a symlink'
    resolved = cursor.resolve(strict=False)
    if not _inside(root, resolved):
        return None, 'must remain inside the repository'
    if not resolved.is_file():
        return None, 'does not exist as a regular file'
    try:
        size = resolved.stat().st_size
    except OSError as error:
        return None, f'cannot be inspected: {error}'
    if size > max_bytes:
        return None, f'exceeds the {max_bytes}-byte limit'
    return resolved, None


def _digest(path: Path) -> str:
    return f'sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}'


def _load_json(path: Path) -> Any:
    def reject_constant(value: str):
        raise ValueError(f'non-finite JSON constant is forbidden: {value}')

    with path.open('r', encoding='utf-8') as handle:
        return json.load(handle, parse_constant=reject_constant)


def _audit_source(engine: str, source: object, label: str) -> list[Finding]:
    findings: list[Finding] = []
    if not isinstance(source, dict):
        return [Finding('PARITY-BIND-020', label, 'trace source must be an object')]
    if source.get('provider') != engine:
        findings.append(Finding('PARITY-BIND-021', label, 'source provider must equal the evidence engine'))
    repository = source.get('repository')
    if not isinstance(repository, str) or not REPOSITORY.fullmatch(repository):
        findings.append(Finding('PARITY-BIND-022', label, 'source repository must be OWNER/REPOSITORY'))
    attempt = source.get('attempt')
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        findings.append(Finding('PARITY-BIND-023', label, 'source attempt must be a positive integer'))
    if engine == 'github-actions':
        run_id = source.get('run_id')
        if not isinstance(run_id, str) or not run_id.isdigit() or len(run_id) > 30:
            findings.append(Finding('PARITY-BIND-024', label, 'GitHub source requires a bounded decimal run_id'))
    else:
        assignment_id = source.get('assignment_id')
        if not isinstance(assignment_id, str) or not OPAQUE_ID.fullmatch(assignment_id):
            findings.append(Finding('PARITY-BIND-025', label, 'clone source requires a bounded opaque assignment_id'))
    return findings


def _load_bound_trace(
    root: Path,
    feature_id: str,
    feature: dict[str, Any],
    evidence: dict[str, Any],
    index: int,
) -> tuple[dict[str, Any] | None, list[Finding]]:
    label = f'{feature_id}.evidence[{index}]'
    findings: list[Finding] = []
    engine = evidence.get('engine')
    case_name = evidence.get('case')
    platform = evidence.get('platform')
    workflow_sha = evidence.get('workflow_sha')
    runner_version = evidence.get('runner_version')

    if engine not in ENGINES or case_name not in CASES:
        return None, [Finding('PARITY-BIND-001', label, 'engine and case must be recognized before binding')]
    if not isinstance(platform, str) or not PLATFORM.fullmatch(platform):
        findings.append(Finding('PARITY-BIND-002', label, 'platform must be a lowercase slug'))
    if not isinstance(workflow_sha, str) or not EXACT_SHA.fullmatch(workflow_sha):
        findings.append(Finding('PARITY-BIND-003', label, 'workflow_sha must be exact lowercase hex'))
    if not isinstance(runner_version, str) or not runner_version.strip() or len(runner_version) > 200:
        findings.append(Finding('PARITY-BIND-004', label, 'runner_version must be bounded and non-empty'))

    expected_fixture = feature.get(f'{case_name}_fixture')
    fixture_value = evidence.get('fixture')
    if fixture_value != expected_fixture:
        findings.append(Finding('PARITY-BIND-005', label, f'fixture must equal the feature {case_name}_fixture'))
    fixture_path, fixture_error = _repo_file(root, fixture_value, max_bytes=MAX_FIXTURE_BYTES)
    if fixture_error:
        findings.append(Finding('PARITY-BIND-006', label, fixture_error))
        fixture_digest = None
    else:
        assert fixture_path is not None
        fixture_digest = _digest(fixture_path)
        if evidence.get('fixture_sha256') != fixture_digest:
            findings.append(Finding('PARITY-BIND-007', label, 'fixture_sha256 does not match the checked-in fixture bytes'))

    trace_path, trace_error = _repo_file(
        root,
        evidence.get('trace'),
        max_bytes=MAX_TRACE_BYTES,
        required_prefix='conformance/evidence',
    )
    if trace_error:
        findings.append(Finding('PARITY-BIND-008', label, trace_error))
        return None, findings
    assert trace_path is not None
    expected_trace_digest = evidence.get('trace_sha256')
    if not isinstance(expected_trace_digest, str) or not SHA256.fullmatch(expected_trace_digest):
        findings.append(Finding('PARITY-BIND-009', label, 'trace_sha256 must be lowercase sha256:<64 hex>'))
    elif _digest(trace_path) != expected_trace_digest:
        findings.append(Finding('PARITY-BIND-010', label, 'trace_sha256 does not match the checked-in trace bytes'))

    try:
        document = _load_json(trace_path)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        findings.append(Finding('PARITY-BIND-011', label, f'accepted evidence must be one bounded JSON trace object: {error}'))
        return None, findings
    if not isinstance(document, dict):
        findings.append(Finding('PARITY-BIND-012', label, 'accepted evidence must be a trace object, not a bare array or JSON Lines'))
        return None, findings

    expected = {
        'schema': TRACE_SCHEMA,
        'engine': engine,
        'case': case_name,
        'platform': platform,
        'workflow_sha': workflow_sha,
        'fixture': fixture_value,
        'fixture_sha256': fixture_digest,
        'runner_version': runner_version,
        'normalization': 'gha-indie-worker.canonical-trace.v1',
    }
    for key, value in expected.items():
        if document.get(key) != value:
            findings.append(Finding('PARITY-BIND-013', f'{label}.{key}', 'trace metadata does not match the catalog evidence entry'))

    capture_tool_sha = document.get('capture_tool_sha256')
    if not isinstance(capture_tool_sha, str) or not SHA256.fullmatch(capture_tool_sha):
        findings.append(Finding('PARITY-BIND-014', label, 'trace must bind the capture tool with capture_tool_sha256'))
    findings.extend(_audit_source(engine, document.get('source'), f'{label}.source'))

    events = document.get('events')
    if not isinstance(events, list) or not events:
        findings.append(Finding('PARITY-BIND-015', label, 'accepted trace must contain at least one event'))
        return None, findings
    try:
        COMPARE._validate_events(events)
    except COMPARE.TraceError as error:
        findings.append(Finding('PARITY-BIND-016', label, f'trace events are invalid: {error}'))
        return None, findings

    return document, findings


def audit(root: Path, *, catalog_path: str = CATALOG_PATH) -> list[Finding]:
    root = root.resolve()
    findings: list[Finding] = []
    catalog_file, catalog_error = _repo_file(root, catalog_path, max_bytes=MAX_CATALOG_BYTES)
    if catalog_error:
        return [Finding('PARITY-BIND-030', catalog_path, catalog_error)]
    assert catalog_file is not None
    try:
        catalog = _load_json(catalog_file)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
        return [Finding('PARITY-BIND-031', catalog_path, f'unable to parse catalog: {error}')]
    if not isinstance(catalog, dict) or not isinstance(catalog.get('features'), list):
        return [Finding('PARITY-BIND-032', catalog_path, 'catalog must contain a features array')]

    for feature_index, feature in enumerate(catalog['features'], start=1):
        if not isinstance(feature, dict):
            continue
        feature_id = feature.get('id')
        if not isinstance(feature_id, str):
            feature_id = f'features[{feature_index}]'
        evidence_entries = feature.get('evidence')
        if not isinstance(evidence_entries, list) or not evidence_entries:
            continue

        loaded: dict[tuple[str, str, str], dict[str, Any]] = {}
        for index, evidence in enumerate(evidence_entries, start=1):
            if not isinstance(evidence, dict):
                findings.append(Finding('PARITY-BIND-033', f'{feature_id}.evidence[{index}]', 'evidence entry must be an object'))
                continue
            document, entry_findings = _load_bound_trace(root, feature_id, feature, evidence, index)
            findings.extend(entry_findings)
            engine = evidence.get('engine')
            case_name = evidence.get('case')
            platform = evidence.get('platform')
            if document is not None and engine in ENGINES and case_name in CASES and isinstance(platform, str):
                key = (engine, case_name, platform)
                if key in loaded:
                    findings.append(Finding('PARITY-BIND-034', feature_id, f'duplicate bound trace for {key}'))
                loaded[key] = document

        if feature.get('status') != 'supported':
            continue

        for case_name in sorted(CASES):
            reference_platforms = {
                platform for engine, observed_case, platform in loaded
                if engine == 'github-actions' and observed_case == case_name
            }
            clone_platforms = {
                platform for engine, observed_case, platform in loaded
                if engine == 'gha-indie-worker' and observed_case == case_name
            }
            if reference_platforms != clone_platforms:
                findings.append(Finding(
                    'PARITY-BIND-035',
                    feature_id,
                    f'{case_name} reference and clone evidence must cover the identical platform set',
                ))
            for platform in sorted(reference_platforms & clone_platforms):
                reference = loaded[('github-actions', case_name, platform)]
                clone = loaded[('gha-indie-worker', case_name, platform)]
                differences = COMPARE.compare_events(reference['events'], clone['events'])
                for difference in differences:
                    findings.append(Finding(
                        'PARITY-BIND-036',
                        f'{feature_id}.{case_name}.{platform}',
                        difference.render(),
                    ))

    return sorted(set(findings))


def main() -> int:
    root = Path(os.environ.get('GHA_PARITY_ROOT', Path(__file__).resolve().parents[1]))
    catalog_path = os.environ.get('GHA_PARITY_CATALOG', CATALOG_PATH)
    findings = audit(root, catalog_path=catalog_path)
    if findings:
        print(f'GitHub Actions evidence binding audit failed with {len(findings)} finding(s):', file=sys.stderr)
        for finding in findings:
            print(f'- {finding.render()}', file=sys.stderr)
        return 1
    print('GitHub Actions evidence bindings and paired traces passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

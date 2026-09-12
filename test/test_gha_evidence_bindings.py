from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / 'tools' / 'audit_gha_evidence_bindings.py'
SPEC = importlib.util.spec_from_file_location('audit_gha_evidence_bindings', MODULE_PATH)
assert SPEC and SPEC.loader
BIND = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BIND
SPEC.loader.exec_module(BIND)


class EvidenceRepository:
    def __init__(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.catalog = {
            'schema': 'gha-indie-worker.parity-catalog.v1',
            'features': [],
        }

    def close(self) -> None:
        self._temp.cleanup()

    def write(self, path: str, content: str) -> Path:
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8')
        return destination

    def save_catalog(self) -> None:
        self.write(BIND.CATALOG_PATH, json.dumps(self.catalog, indent=2) + '\n')

    def feature(self, *, status: str = 'supported') -> dict:
        feature = {
            'id': 'expression.coercion',
            'status': status,
            'positive_fixture': 'conformance/fixtures/workflows/expression-positive.yml',
            'adversarial_fixture': 'conformance/fixtures/workflows/expression-adversarial.yml',
            'evidence': [],
        }
        self.catalog['features'] = [feature]
        self.write(feature['positive_fixture'], 'name: positive\non: workflow_dispatch\njobs: {}\n')
        self.write(feature['adversarial_fixture'], 'name: adversarial\non: workflow_dispatch\njobs: {}\n')
        return feature

    def add_evidence(
        self,
        feature: dict,
        *,
        engine: str,
        case_name: str,
        platform: str = 'linux-x64',
        events: list[dict] | None = None,
        metadata_overrides: dict | None = None,
        entry_overrides: dict | None = None,
    ) -> dict:
        fixture = feature[f'{case_name}_fixture']
        fixture_path = self.root / fixture
        fixture_digest = f"sha256:{hashlib.sha256(fixture_path.read_bytes()).hexdigest()}"
        workflow_sha = 'a' * 40
        runner_version = 'github-runner-2.999.0' if engine == 'github-actions' else 'gha-indie-worker-test'
        trace_name = f'{feature["id"]}-{case_name}-{platform}-{engine}.json'
        trace_path = f'conformance/evidence/{trace_name}'
        source = {
            'provider': engine,
            'repository': 'gha-conformance/reference-fixtures',
            'attempt': 1,
        }
        if engine == 'github-actions':
            source['run_id'] = '123456789'
        else:
            source['assignment_id'] = 'assignment:12345678'
        document = {
            'schema': BIND.TRACE_SCHEMA,
            'engine': engine,
            'case': case_name,
            'platform': platform,
            'workflow_sha': workflow_sha,
            'fixture': fixture,
            'fixture_sha256': fixture_digest,
            'runner_version': runner_version,
            'normalization': 'gha-indie-worker.canonical-trace.v1',
            'capture_tool_sha256': f"sha256:{'c' * 64}",
            'source': source,
            'events': events or [
                {'event': 'job.started', 'scope': 'verify'},
                {'event': 'job.completed', 'scope': 'verify', 'status': 'success'},
            ],
        }
        if metadata_overrides:
            document.update(metadata_overrides)
        trace = self.write(trace_path, json.dumps(document, indent=2) + '\n')
        entry = {
            'engine': engine,
            'case': case_name,
            'platform': platform,
            'workflow_sha': workflow_sha,
            'runner_version': runner_version,
            'fixture': fixture,
            'fixture_sha256': fixture_digest,
            'trace': trace_path,
            'trace_sha256': f"sha256:{hashlib.sha256(trace.read_bytes()).hexdigest()}",
        }
        if entry_overrides:
            entry.update(entry_overrides)
        feature['evidence'].append(entry)
        return entry

    def complete_supported_feature(self) -> dict:
        feature = self.feature()
        for case_name in ('positive', 'adversarial'):
            self.add_evidence(feature, engine='github-actions', case_name=case_name)
            self.add_evidence(feature, engine='gha-indie-worker', case_name=case_name)
        return feature

    def findings(self):
        self.save_catalog()
        return BIND.audit(self.root)

    def codes(self) -> set[str]:
        return {finding.code for finding in self.findings()}


class EvidenceBindingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = EvidenceRepository()
        self.addCleanup(self.repo.close)

    def test_empty_unverified_catalog_passes(self) -> None:
        self.repo.feature(status='unverified')
        self.assertEqual(self.repo.findings(), [])

    def test_supported_feature_with_bound_matching_pairs_passes(self) -> None:
        self.repo.complete_supported_feature()
        self.assertEqual(self.repo.findings(), [])

    def test_semantic_difference_fails_without_echoing_values(self) -> None:
        feature = self.feature_with_reference_secret()
        clone_entry = next(
            entry for entry in feature['evidence']
            if entry['engine'] == 'gha-indie-worker' and entry['case'] == 'positive'
        )
        clone_path = self.repo.root / clone_entry['trace']
        document = json.loads(clone_path.read_text(encoding='utf-8'))
        document['events'][1]['status'] = 'failure'
        document['events'][1]['data'] = {'token': 'clone-secret-value'}
        clone_path.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
        clone_entry['trace_sha256'] = f"sha256:{hashlib.sha256(clone_path.read_bytes()).hexdigest()}"

        findings = self.repo.findings()
        difference = next(finding for finding in findings if finding.code == 'PARITY-BIND-036')
        rendered = difference.render()
        self.assertIn('/status', rendered)
        self.assertIn('sha256=', rendered)
        self.assertNotIn('reference-secret-value', rendered)
        self.assertNotIn('clone-secret-value', rendered)

    def feature_with_reference_secret(self) -> dict:
        feature = self.repo.complete_supported_feature()
        reference_entry = next(
            entry for entry in feature['evidence']
            if entry['engine'] == 'github-actions' and entry['case'] == 'positive'
        )
        reference_path = self.repo.root / reference_entry['trace']
        document = json.loads(reference_path.read_text(encoding='utf-8'))
        document['events'][1]['data'] = {'token': 'reference-secret-value'}
        reference_path.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
        reference_entry['trace_sha256'] = f"sha256:{hashlib.sha256(reference_path.read_bytes()).hexdigest()}"
        return feature

    def test_fixture_tampering_is_rejected(self) -> None:
        feature = self.repo.complete_supported_feature()
        (self.repo.root / feature['positive_fixture']).write_text(
            'name: tampered\non: workflow_dispatch\njobs: {}\n',
            encoding='utf-8',
        )
        codes = self.repo.codes()
        self.assertIn('PARITY-BIND-007', codes)
        self.assertIn('PARITY-BIND-013', codes)

    def test_trace_metadata_must_match_catalog_entry(self) -> None:
        feature = self.repo.feature()
        self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
            metadata_overrides={'engine': 'gha-indie-worker'},
        )
        self.assertIn('PARITY-BIND-013', self.repo.codes())

    def test_reference_and_clone_platform_sets_must_be_identical(self) -> None:
        feature = self.repo.complete_supported_feature()
        self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
            platform='windows-x64',
        )
        self.assertIn('PARITY-BIND-035', self.repo.codes())

    def test_bare_array_cannot_be_accepted_as_provenance_evidence(self) -> None:
        feature = self.repo.feature(status='partial')
        entry = self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
        )
        path = self.repo.root / entry['trace']
        path.write_text(json.dumps([{'event': 'job.started'}]) + '\n', encoding='utf-8')
        entry['trace_sha256'] = f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"
        self.assertIn('PARITY-BIND-012', self.repo.codes())

    def test_empty_event_trace_is_rejected(self) -> None:
        feature = self.repo.feature(status='partial')
        self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
            metadata_overrides={'events': []},
        )
        self.assertIn('PARITY-BIND-015', self.repo.codes())

    def test_capture_tool_and_source_provenance_are_required(self) -> None:
        feature = self.repo.feature(status='partial')
        self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
            metadata_overrides={'capture_tool_sha256': None, 'source': {}},
        )
        codes = self.repo.codes()
        self.assertIn('PARITY-BIND-014', codes)
        self.assertIn('PARITY-BIND-021', codes)
        self.assertIn('PARITY-BIND-022', codes)
        self.assertIn('PARITY-BIND-023', codes)
        self.assertIn('PARITY-BIND-024', codes)

    def test_trace_symlink_is_rejected(self) -> None:
        feature = self.repo.feature(status='partial')
        entry = self.repo.add_evidence(
            feature,
            engine='github-actions',
            case_name='positive',
        )
        trace_path = self.repo.root / entry['trace']
        target = self.repo.write('outside-trace.json', trace_path.read_text(encoding='utf-8'))
        trace_path.unlink()
        os.symlink(target, trace_path)
        self.assertIn('PARITY-BIND-008', self.repo.codes())

    def test_event_count_mismatch_is_a_semantic_failure(self) -> None:
        feature = self.repo.complete_supported_feature()
        entry = next(
            item for item in feature['evidence']
            if item['engine'] == 'gha-indie-worker' and item['case'] == 'adversarial'
        )
        path = self.repo.root / entry['trace']
        document = json.loads(path.read_text(encoding='utf-8'))
        document['events'].append({'event': 'cleanup.completed', 'status': 'success'})
        path.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
        entry['trace_sha256'] = f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}"
        findings = self.repo.findings()
        self.assertTrue(any(
            finding.code == 'PARITY-BIND-036' and 'event-count-mismatch' in finding.message
            for finding in findings
        ))


if __name__ == '__main__':
    unittest.main()

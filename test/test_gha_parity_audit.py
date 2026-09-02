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

MODULE_PATH = Path(__file__).resolve().parents[1] / 'tools' / 'audit_gha_parity.py'
SPEC = importlib.util.spec_from_file_location('audit_gha_parity', MODULE_PATH)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = AUDIT
SPEC.loader.exec_module(AUDIT)

CATALOG_SOURCE = Path(__file__).resolve().parents[1] / AUDIT.DEFAULT_CATALOG_PATH


class ParityRepository:
    def __init__(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)
        self.catalog = json.loads(CATALOG_SOURCE.read_text(encoding='utf-8'))
        for feature in self.catalog['features']:
            for key in ('positive_fixture', 'adversarial_fixture'):
                value = feature.get(key)
                if value:
                    self.write(value, 'name: fixture\non: workflow_dispatch\njobs: {}\n')
        self.save_catalog()

    def close(self) -> None:
        self._temp.cleanup()

    def write(self, path: str, content: str) -> Path:
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8')
        return destination

    def save_catalog(self) -> None:
        self.write(AUDIT.DEFAULT_CATALOG_PATH, json.dumps(self.catalog, indent=2) + '\n')

    def feature(self, feature_id: str) -> dict:
        return next(item for item in self.catalog['features'] if item['id'] == feature_id)

    def evidence(self, path: str, *, engine: str, case_name: str, platform: str = 'linux-x64') -> dict:
        trace = self.write(path, json.dumps([{'event': 'job.completed', 'status': 'success'}]) + '\n')
        digest = f"sha256:{hashlib.sha256(trace.read_bytes()).hexdigest()}"
        return {
            'engine': engine,
            'case': case_name,
            'platform': platform,
            'workflow_sha': 'a' * 40,
            'runner_version': 'conformance-test',
            'trace': path,
            'trace_sha256': digest,
        }

    def findings(self, *, require_full: bool = False):
        self.save_catalog()
        return AUDIT.audit(self.root, require_full=require_full)[0]

    def codes(self, *, require_full: bool = False) -> set[str]:
        return {finding.code for finding in self.findings(require_full=require_full)}


class GhaParityAuditTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = ParityRepository()
        self.addCleanup(self.repo.close)

    def test_unverified_catalog_is_structurally_honest(self) -> None:
        findings, counts = AUDIT.audit(self.repo.root)
        self.assertEqual(findings, [])
        self.assertEqual(counts['unverified'], len(self.repo.catalog['features']))
        self.assertFalse(self.repo.catalog['claims']['full_parity'])

    def test_mandatory_feature_cannot_be_silently_removed(self) -> None:
        self.repo.catalog['features'] = [
            feature for feature in self.repo.catalog['features']
            if feature['id'] != 'security.secret-masking'
        ]
        self.assertIn('PARITY-FEATURE-011', self.repo.codes())

    def test_premature_full_parity_claim_fails_closed(self) -> None:
        self.repo.catalog['claims']['full_parity'] = True
        self.assertIn('PARITY-CLAIM-003', self.repo.codes())

    def test_drop_in_claim_requires_full_parity(self) -> None:
        self.repo.catalog['claims']['production_drop_in_replacement'] = True
        self.assertIn('PARITY-CLAIM-004', self.repo.codes())

    def test_supported_feature_requires_matched_positive_and_adversarial_evidence(self) -> None:
        feature = self.repo.feature('expression.coercion')
        feature['status'] = 'supported'
        feature['evidence'] = [
            self.repo.evidence(
                'conformance/evidence/expression-positive-reference.json',
                engine='github-actions',
                case_name='positive',
            ),
            self.repo.evidence(
                'conformance/evidence/expression-positive-clone.json',
                engine='gha-indie-worker',
                case_name='positive',
            ),
            self.repo.evidence(
                'conformance/evidence/expression-adversarial-reference.json',
                engine='github-actions',
                case_name='adversarial',
            ),
            self.repo.evidence(
                'conformance/evidence/expression-adversarial-clone.json',
                engine='gha-indie-worker',
                case_name='adversarial',
            ),
        ]
        self.assertEqual(self.repo.findings(), [])

        feature['evidence'] = [
            item for item in feature['evidence']
            if not (item['engine'] == 'gha-indie-worker' and item['case'] == 'adversarial')
        ]
        self.assertIn('PARITY-STATE-005', self.repo.codes())

    def test_trace_digest_tampering_is_rejected(self) -> None:
        feature = self.repo.feature('expression.coercion')
        evidence = self.repo.evidence(
            'conformance/evidence/tampered.json',
            engine='github-actions',
            case_name='positive',
        )
        evidence['trace_sha256'] = f"sha256:{'0' * 64}"
        feature['status'] = 'partial'
        feature['evidence'] = [evidence]
        self.assertIn('PARITY-EVIDENCE-011', self.repo.codes())

    def test_fixture_symlink_is_rejected(self) -> None:
        feature = self.repo.feature('expression.coercion')
        fixture_path = self.repo.root / feature['positive_fixture']
        fixture_path.unlink()
        target = self.repo.write('outside.yml', 'name: target\non: workflow_dispatch\njobs: {}\n')
        os.symlink(target, fixture_path)
        self.assertIn('PARITY-FIXTURE-001', self.repo.codes())

    def test_require_full_mode_rejects_unproven_catalog(self) -> None:
        self.assertIn('PARITY-CLAIM-005', self.repo.codes(require_full=True))


if __name__ == '__main__':
    unittest.main()

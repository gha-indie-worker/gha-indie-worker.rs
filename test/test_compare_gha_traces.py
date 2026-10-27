from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

MODULE_PATH = Path(__file__).resolve().parents[1] / 'tools' / 'compare_gha_traces.py'
SPEC = importlib.util.spec_from_file_location('compare_gha_traces', MODULE_PATH)
assert SPEC and SPEC.loader
COMPARE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = COMPARE
SPEC.loader.exec_module(COMPARE)


class TraceRepository:
    def __init__(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.root = Path(self._temp.name)

    def close(self) -> None:
        self._temp.cleanup()

    def write(self, name: str, content: str) -> Path:
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf-8')
        return path

    def json(self, name: str, document) -> Path:
        return self.write(name, json.dumps(document) + '\n')


class TraceComparatorTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repo = TraceRepository()
        self.addCleanup(self.repo.close)

    def test_runner_ids_timestamps_and_workspace_roots_are_normalized(self) -> None:
        reference = [{
            'event': 'step.completed',
            'seq': 8,
            'timestamp': '2026-09-01T12:00:00Z',
            'runner_name': 'GitHub Actions 1',
            'data': {
                'workspace_file': '/home/runner/work/sample/sample/src/main.rs',
                'temp_file': '/home/runner/work/_temp/abc/result.json',
                'status': 'success',
            },
        }]
        clone = [{
            'event': 'step.completed',
            'seq': 1002,
            'timestamp': '2026-09-01T12:00:03Z',
            'runner_name': 'indie-worker-7',
            'data': {
                'workspace_file': '/srv/gha/jobs/42/workspace/src/main.rs',
                'temp_file': '/srv/gha/jobs/42/temp/abc/result.json',
                'status': 'success',
            },
        }]
        differences = COMPARE.compare_events(
            reference,
            clone,
            clone_roots={
                'workspace': '/srv/gha/jobs/42/workspace',
                'temp': '/srv/gha/jobs/42/temp',
            },
        )
        self.assertEqual(differences, [])

    def test_semantic_mismatch_reports_paths_and_hashes_not_values(self) -> None:
        reference = [{'event': 'step.completed', 'status': 'success', 'data': {'token': 'reference-secret'}}]
        clone = [{'event': 'step.completed', 'status': 'failure', 'data': {'token': 'clone-secret'}}]
        differences = COMPARE.compare_events(reference, clone)
        self.assertEqual(len(differences), 1)
        rendered = differences[0].render()
        self.assertIn('/status', rendered)
        self.assertIn('/data/token', rendered)
        self.assertNotIn('reference-secret', rendered)
        self.assertNotIn('clone-secret', rendered)
        self.assertIn('sha256=', rendered)

    def test_event_order_is_semantic(self) -> None:
        reference = [
            {'event': 'job.started'},
            {'event': 'job.completed', 'status': 'success'},
        ]
        clone = [
            {'event': 'job.completed', 'status': 'success'},
            {'event': 'job.started'},
        ]
        differences = COMPARE.compare_events(reference, clone)
        self.assertTrue(differences)
        self.assertEqual(differences[0].kind, 'event-order-or-name-mismatch')

    def test_json_lines_and_trace_object_formats_are_supported(self) -> None:
        jsonl = self.repo.write(
            'reference.jsonl',
            '{"event":"job.started"}\n{"event":"job.completed","status":"success"}\n',
        )
        wrapped = self.repo.json('clone.json', {
            'schema': COMPARE.TRACE_SCHEMA,
            'engine': 'gha-indie-worker',
            'events': [
                {'event': 'job.started'},
                {'event': 'job.completed', 'status': 'success'},
            ],
        })
        self.assertEqual(COMPARE.load_trace(jsonl), COMPARE.load_trace(wrapped))

    def test_trace_size_limit_fails_before_parsing(self) -> None:
        path = self.repo.write('large.json', '[{"event":"job.started"}]')
        with self.assertRaises(COMPARE.TraceError):
            COMPARE.load_trace(path, max_bytes=4)

    def test_event_count_limit_fails_closed(self) -> None:
        path = self.repo.json('events.json', [
            {'event': 'one'},
            {'event': 'two'},
        ])
        with self.assertRaises(COMPARE.TraceError):
            COMPARE.load_trace(path, max_events=1)

    def test_symlinked_trace_is_rejected(self) -> None:
        target = self.repo.json('real.json', [{'event': 'job.started'}])
        link = self.repo.root / 'link.json'
        os.symlink(target, link)
        with self.assertRaises(COMPARE.TraceError):
            COMPARE.load_trace(link)

    def test_invalid_event_shape_is_rejected(self) -> None:
        path = self.repo.json('invalid.json', [{'status': 'success'}])
        with self.assertRaises(COMPARE.TraceError):
            COMPARE.load_trace(path)


if __name__ == '__main__':
    unittest.main()

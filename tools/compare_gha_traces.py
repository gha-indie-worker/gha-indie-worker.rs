#!/usr/bin/env python3
"""Compare normalized real-GitHub and gha-indie-worker execution traces.

The comparator deliberately reports only event names, JSON-pointer paths, and
content fingerprints. It never echoes differing values, which may contain
secrets or other untrusted workflow output.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, Iterable

TRACE_SCHEMA = 'gha-indie-worker.trace.v1'
MAX_TRACE_BYTES = 16_777_216
MAX_EVENTS = 100_000
MAX_DEPTH = 64
MAX_DIFFERENCES = 100
TRUE_VALUES = {'1', 'true', 'yes', 'on'}
EVENT_NAME = re.compile(r'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$')

VOLATILE_KEYS = frozenset({
    'captured_at',
    'completed_at',
    'correlation_id',
    'delivery_id',
    'duration_ms',
    'elapsed_ms',
    'job_id',
    'message_id',
    'request_id',
    'run_id',
    'runner_name',
    'session_id',
    'started_at',
    'step_id',
    'timestamp',
    'timestamp_ms',
    'timestamp_ns',
    'timeline_id',
    'trace_id',
    'worker_id',
})
TOP_LEVEL_VOLATILE_KEYS = VOLATILE_KEYS | {'engine', 'seq', 'sequence'}

POSIX_TEMP_PATTERNS = (
    re.compile(r'/home/runner/work/_temp(?:/[A-Za-z0-9._/-]+)?'),
    re.compile(r'/__w/_temp(?:/[A-Za-z0-9._/-]+)?'),
    re.compile(r'/tmp(?:/[A-Za-z0-9._/-]+)?'),
)
POSIX_WORKSPACE_PATTERNS = (
    re.compile(r'/home/runner/work/[^/\s]+/[^/\s]+'),
    re.compile(r'/__w/[^/\s]+/[^/\s]+'),
)
WINDOWS_TEMP_PATTERNS = (
    re.compile(r'(?i)[A-Z]:\\a\\_temp(?:\\[^\s"\']+)?'),
    re.compile(r'(?i)[A-Z]:\\Users\\RUNNER~1\\AppData\\Local\\Temp(?:\\[^\s"\']+)?'),
)
WINDOWS_WORKSPACE_PATTERNS = (
    re.compile(r'(?i)[A-Z]:\\a\\[^\\\s]+\\[^\\\s]+'),
)


class TraceError(ValueError):
    pass


@dataclass(frozen=True)
class Difference:
    index: int | None
    kind: str
    reference_event: str | None
    clone_event: str | None
    paths: tuple[str, ...]
    reference_fingerprint: str | None
    clone_fingerprint: str | None

    def render(self) -> str:
        location = 'trace' if self.index is None else f'event[{self.index}]'
        events = f'reference={self.reference_event or "<none>"}, clone={self.clone_event or "<none>"}'
        path_text = ', '.join(self.paths) if self.paths else '<structure>'
        fingerprints = (
            f'reference_sha256={self.reference_fingerprint or "<none>"}, '
            f'clone_sha256={self.clone_fingerprint or "<none>"}'
        )
        return f'{self.kind} at {location} ({events}); paths={path_text}; {fingerprints}'


def _file_bytes(path: Path, max_bytes: int) -> bytes:
    if path.is_symlink():
        raise TraceError(f'{path} must not be a symlink')
    if not path.is_file():
        raise TraceError(f'{path} is not a regular file')
    size = path.stat().st_size
    if size > max_bytes:
        raise TraceError(f'{path} exceeds the {max_bytes}-byte trace limit')
    return path.read_bytes()


def _validate_value(value: Any, depth: int = 0) -> None:
    if depth > MAX_DEPTH:
        raise TraceError(f'trace value exceeds maximum nesting depth {MAX_DEPTH}')
    if value is None or isinstance(value, (bool, int, float, str)):
        return
    if isinstance(value, list):
        for item in value:
            _validate_value(item, depth + 1)
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise TraceError('trace object keys must be strings')
            _validate_value(item, depth + 1)
        return
    raise TraceError(f'unsupported trace value type: {type(value).__name__}')


def _extract_events(document: Any) -> list[dict[str, Any]]:
    if isinstance(document, list):
        events = document
    elif isinstance(document, dict):
        schema = document.get('schema')
        if schema is not None and schema != TRACE_SCHEMA:
            raise TraceError(f'trace schema must be {TRACE_SCHEMA}')
        events = document.get('events')
        if not isinstance(events, list):
            raise TraceError('trace object must contain an events array')
    else:
        raise TraceError('trace must be a JSON array, a trace object, or JSON Lines')
    return _validate_events(events)


def _validate_events(events: list[Any], max_events: int = MAX_EVENTS) -> list[dict[str, Any]]:
    if len(events) > max_events:
        raise TraceError(f'trace contains more than {max_events} events')
    validated: list[dict[str, Any]] = []
    for index, event in enumerate(events):
        if not isinstance(event, dict):
            raise TraceError(f'event[{index}] must be an object')
        event_name = event.get('event')
        if not isinstance(event_name, str) or not EVENT_NAME.fullmatch(event_name):
            raise TraceError(f'event[{index}].event must be a bounded event-name string')
        _validate_value(event)
        validated.append(event)
    return validated


def load_trace(
    path: Path,
    *,
    max_bytes: int = MAX_TRACE_BYTES,
    max_events: int = MAX_EVENTS,
) -> list[dict[str, Any]]:
    data = _file_bytes(path, max_bytes)
    if b'\x00' in data:
        raise TraceError(f'{path} contains NUL bytes')
    try:
        text = data.decode('utf-8')
    except UnicodeDecodeError as error:
        raise TraceError(f'{path} is not UTF-8') from error
    stripped = text.strip()
    if not stripped:
        raise TraceError(f'{path} is empty')

    try:
        if stripped[0] in '[{':
            document = json.loads(stripped)
            events = _extract_events(document)
        else:
            parsed: list[Any] = []
            for line_number, line in enumerate(text.splitlines(), start=1):
                if not line.strip():
                    continue
                try:
                    parsed.append(json.loads(line))
                except json.JSONDecodeError as error:
                    raise TraceError(f'{path} has invalid JSON on line {line_number}') from error
            events = _validate_events(parsed, max_events=max_events)
    except json.JSONDecodeError as error:
        raise TraceError(f'{path} contains invalid JSON') from error

    if len(events) > max_events:
        raise TraceError(f'trace contains more than {max_events} events')
    return events


def _replace_root(value: str, root: str, token: str) -> str:
    if not root:
        return value
    variants = {root.rstrip('/\\'), root.rstrip('/\\').replace('\\', '/'), root.rstrip('/\\').replace('/', '\\')}
    result = value
    for variant in sorted((item for item in variants if item), key=len, reverse=True):
        result = result.replace(variant, token)
    return result


def _normalize_string(value: str, roots: dict[str, str]) -> str:
    normalized = value.replace('\r\n', '\n').replace('\r', '\n')
    normalized = _replace_root(normalized, roots.get('workspace', ''), '${WORKSPACE}')
    normalized = _replace_root(normalized, roots.get('temp', ''), '${RUNNER_TEMP}')
    for pattern in POSIX_TEMP_PATTERNS:
        normalized = pattern.sub('${RUNNER_TEMP}', normalized)
    for pattern in WINDOWS_TEMP_PATTERNS:
        normalized = pattern.sub('${RUNNER_TEMP}', normalized)
    for pattern in POSIX_WORKSPACE_PATTERNS:
        normalized = pattern.sub('${WORKSPACE}', normalized)
    for pattern in WINDOWS_WORKSPACE_PATTERNS:
        normalized = pattern.sub('${WORKSPACE}', normalized)
    return normalized


def normalize_value(value: Any, *, roots: dict[str, str] | None = None, top_level: bool = False) -> Any:
    roots = roots or {}
    if isinstance(value, dict):
        ignored = TOP_LEVEL_VOLATILE_KEYS if top_level else VOLATILE_KEYS
        return {
            key: normalize_value(item, roots=roots, top_level=False)
            for key, item in sorted(value.items())
            if key not in ignored
        }
    if isinstance(value, list):
        return [normalize_value(item, roots=roots, top_level=False) for item in value]
    if isinstance(value, str):
        return _normalize_string(value, roots)
    return value


def normalize_events(events: Iterable[dict[str, Any]], *, roots: dict[str, str] | None = None) -> list[dict[str, Any]]:
    return [normalize_value(event, roots=roots, top_level=True) for event in events]


def _fingerprint(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def _escape_pointer(value: str) -> str:
    return value.replace('~', '~0').replace('/', '~1')


def _different_paths(reference: Any, clone: Any, path: str = '') -> list[str]:
    if type(reference) is not type(clone):
        return [path or '/']
    if isinstance(reference, dict):
        paths: list[str] = []
        keys = sorted(set(reference) | set(clone))
        for key in keys:
            child = f'{path}/{_escape_pointer(key)}'
            if key not in reference or key not in clone:
                paths.append(child)
            else:
                paths.extend(_different_paths(reference[key], clone[key], child))
            if len(paths) >= MAX_DIFFERENCES:
                return paths[:MAX_DIFFERENCES]
        return paths
    if isinstance(reference, list):
        paths = []
        maximum = max(len(reference), len(clone))
        for index in range(maximum):
            child = f'{path}/{index}'
            if index >= len(reference) or index >= len(clone):
                paths.append(child)
            else:
                paths.extend(_different_paths(reference[index], clone[index], child))
            if len(paths) >= MAX_DIFFERENCES:
                return paths[:MAX_DIFFERENCES]
        return paths
    return [] if reference == clone else [path or '/']


def compare_events(
    reference_events: Iterable[dict[str, Any]],
    clone_events: Iterable[dict[str, Any]],
    *,
    reference_roots: dict[str, str] | None = None,
    clone_roots: dict[str, str] | None = None,
) -> list[Difference]:
    reference = normalize_events(reference_events, roots=reference_roots)
    clone = normalize_events(clone_events, roots=clone_roots)
    differences: list[Difference] = []

    if len(reference) != len(clone):
        differences.append(Difference(
            index=None,
            kind='event-count-mismatch',
            reference_event=None,
            clone_event=None,
            paths=('/events',),
            reference_fingerprint=_fingerprint({'count': len(reference)}),
            clone_fingerprint=_fingerprint({'count': len(clone)}),
        ))

    for index in range(min(len(reference), len(clone))):
        reference_event = reference[index]
        clone_event = clone[index]
        if reference_event == clone_event:
            continue
        reference_name = reference_event.get('event') if isinstance(reference_event.get('event'), str) else None
        clone_name = clone_event.get('event') if isinstance(clone_event.get('event'), str) else None
        kind = 'event-order-or-name-mismatch' if reference_name != clone_name else 'event-semantic-mismatch'
        differences.append(Difference(
            index=index,
            kind=kind,
            reference_event=reference_name,
            clone_event=clone_name,
            paths=tuple(_different_paths(reference_event, clone_event)),
            reference_fingerprint=_fingerprint(reference_event),
            clone_fingerprint=_fingerprint(clone_event),
        ))
        if len(differences) >= MAX_DIFFERENCES:
            break

    return differences


def _roots(prefix: str) -> dict[str, str]:
    return {
        'workspace': os.environ.get(f'GHA_{prefix}_WORKSPACE', ''),
        'temp': os.environ.get(f'GHA_{prefix}_TEMP', ''),
    }


def main() -> int:
    reference_value = os.environ.get('GHA_REFERENCE_TRACE', '').strip()
    clone_value = os.environ.get('GHA_CLONE_TRACE', '').strip()
    if not reference_value or not clone_value:
        print('GHA_REFERENCE_TRACE and GHA_CLONE_TRACE are required', file=sys.stderr)
        return 2

    try:
        reference = load_trace(Path(reference_value))
        clone = load_trace(Path(clone_value))
        differences = compare_events(
            reference,
            clone,
            reference_roots=_roots('REFERENCE'),
            clone_roots=_roots('CLONE'),
        )
    except (OSError, TraceError) as error:
        print(f'trace comparison failed to load: {error}', file=sys.stderr)
        return 2

    if differences:
        print(f'GitHub Actions trace comparison found {len(differences)} difference(s):', file=sys.stderr)
        for difference in differences:
            print(f'- {difference.render()}', file=sys.stderr)
        return 1

    print(f'GitHub Actions traces match after bounded normalization ({len(reference)} events)')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

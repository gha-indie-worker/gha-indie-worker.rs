#!/usr/bin/env python3
"""Validate observable actor protocol invariants, independent of the backend.

This validates *recorded traces*. It cannot establish actor conformance until
a real runtime emits these records under trusted test instrumentation.
"""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent

class TraceViolation(ValueError):
    pass

def check_trace(trace):
    try:
        import jsonschema
    except ImportError as exc:
        raise TraceViolation("jsonschema>=4.20 required") from exc
    schema = json.loads((ROOT / "trace.schema.json").read_text())
    jsonschema.Draft202012Validator.check_schema(schema)
    jsonschema.validate(trace, schema)
    actors = set()
    retired = set()
    messages = {}
    received = set()
    owners = {}
    events = trace["events"]
    if len(events) > 100000:
        raise TraceViolation("trace limit exceeded")
    for i, e in enumerate(events):
        if e["seq"] != i:
            raise TraceViolation(f"non-contiguous sequence at {i}")
        kind, actor = e["kind"], e["actor"]
        permitted = {"seq", "kind", "actor"}
        if kind == "send":
            permitted.update(("target", "message_id"))
        elif kind == "admit":
            permitted.add("message_id")
        elif kind == "receive":
            permitted.add("message_id")
        elif kind in ("exclusive_acquire", "exclusive_release"):
            permitted.add("resource")
        if set(e) - permitted:
            raise TraceViolation(f"unexpected fields for {kind} at {i}")
        if kind == "spawn":
            if actor in actors or actor in retired:
                raise TraceViolation(f"actor spawned twice: {actor}")
            actors.add(actor)
            continue
        if actor not in actors:
            raise TraceViolation(f"unknown or terminated actor: {actor}")
        if kind == "admit":
            msg = e["message_id"]
            if msg in messages:
                raise TraceViolation(f"duplicate ingress id: {msg}")
            messages[msg] = actor
        elif kind == "send":
            target, msg = e["target"], e["message_id"]
            if target not in actors:
                raise TraceViolation(f"target not live: {target}")
            if msg in messages:
                raise TraceViolation(f"duplicate send id: {msg}")
            messages[msg] = target
        elif kind == "receive":
            msg = e["message_id"]
            if msg not in messages or messages[msg] != actor or msg in received:
                raise TraceViolation(f"unsent, wrong-target or duplicate receive: {msg}")
            received.add(msg)
        elif kind == "exclusive_acquire":
            resource = e["resource"]
            if resource in owners:
                raise TraceViolation(f"double exclusive owner: {resource}")
            owners[resource] = actor
        elif kind == "exclusive_release":
            resource = e["resource"]
            if owners.get(resource) != actor:
                raise TraceViolation(f"release by non-owner: {resource}")
            del owners[resource]
        elif kind == "terminate":
            if actor in owners.values():
                raise TraceViolation(f"actor terminated while holding an exclusive resource: {actor}")
            actors.remove(actor)
            retired.add(actor)
    if trace["complete"]:
        if actors:
            raise TraceViolation("complete trace contains active actors")
        if owners:
            raise TraceViolation("complete trace contains active exclusive owners")
        if set(messages) != received:
            raise TraceViolation("complete trace has undelivered messages")
    return {"events": len(events), "actors": len(actors) + len(retired),
            "messages_sent": len(messages), "messages_received": len(received)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("trace", type=Path, nargs="*")
    parser.add_argument("--schema-only", action="store_true")
    args = parser.parse_args()
    if not args.trace and not args.schema_only:
        parser.error("provide a trace or --schema-only")
    try:
        import jsonschema
        schema = json.loads((ROOT / "trace.schema.json").read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        if args.schema_only and not args.trace:
            print("TRACE_SCHEMA_OK")
            return 0
        for filename in args.trace:
            print(filename, check_trace(json.loads(filename.read_text())))
        return 0
    except (ValueError, OSError, ImportError, jsonschema.ValidationError) as exc:
        print(f"TRACE_INVALID: {exc}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())

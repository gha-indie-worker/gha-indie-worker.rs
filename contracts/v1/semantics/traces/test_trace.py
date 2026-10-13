#!/usr/bin/env python3
"""Adversarial tests for the backend-neutral actor trace state machine."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from validate import check_trace, TraceViolation

def good():
    events = [
        {"kind":"spawn","actor":"alice"},
        {"kind":"spawn","actor":"bob"},
        {"kind":"exclusive_acquire","actor":"alice","resource":"account"},
        {"kind":"send","actor":"alice","target":"bob","message_id":"m1"},
        {"kind":"receive","actor":"bob","message_id":"m1"},
        {"kind":"exclusive_release","actor":"alice","resource":"account"},
        {"kind":"terminate","actor":"bob"},
        {"kind":"terminate","actor":"alice"},
    ]
    return {"schema_version":"1.0.0","complete":True,
            "events":[dict(seq=i, **event) for i,event in enumerate(events)]}

class ActorTraceProof(unittest.TestCase):
    def assert_invalid(self, trace):
        with self.assertRaises(Exception):
            check_trace(trace)

    def test_good_trace(self):
        metrics = check_trace(good())
        self.assertEqual(metrics["messages_received"], 1)
        self.assertEqual(metrics["actors"], 2)

    def test_external_ingress_roundtrip(self):
        trace = {"schema_version":"1.0.0","complete":True,
                 "events":[
                     {"seq":0,"kind":"spawn","actor":"receiver"},
                     {"seq":1,"kind":"admit","actor":"receiver","message_id":"external_1"},
                     {"seq":2,"kind":"receive","actor":"receiver","message_id":"external_1"},
                     {"seq":3,"kind":"terminate","actor":"receiver"},
                 ]}
        self.assertEqual(check_trace(trace)["messages_received"], 1)
        duplicate = copy.deepcopy(trace)
        duplicate["events"].insert(2, dict(seq=2,kind="admit",actor="receiver",
                                           message_id="external_1"))
        for seq,event in enumerate(duplicate["events"]): event["seq"] = seq
        self.assert_invalid(duplicate)
        missing = copy.deepcopy(trace)
        del missing["events"][1]["message_id"]
        self.assert_invalid(missing)
        wrong = copy.deepcopy(trace)
        wrong["events"][1]["actor"] = "unspawned"
        self.assert_invalid(wrong)

    def test_partial_trace_does_not_imply_eventual_delivery(self):
        t = good()
        t["complete"] = False
        t["events"] = t["events"][:4]
        self.assertEqual(check_trace(t)["messages_received"], 0)
        t["complete"] = True
        self.assert_invalid(t)

    def test_duplicate_delivery(self):
        t = good()
        t["events"].insert(5, {"kind":"receive","actor":"bob","message_id":"m1"})
        for i,e in enumerate(t["events"]): e["seq"] = i
        self.assert_invalid(t)

    def test_double_exclusive_owner(self):
        t = good()
        t["events"].insert(3, {"kind":"exclusive_acquire","actor":"bob","resource":"account"})
        for i,e in enumerate(t["events"]): e["seq"] = i
        self.assert_invalid(t)

    def test_wrong_message_recipient(self):
        t = good()
        t["events"][4]["actor"] = "alice"
        self.assert_invalid(t)

    def test_release_by_nonowner(self):
        t = good()
        t["events"][5]["actor"] = "bob"
        self.assert_invalid(t)

    def test_termination_with_held_resource(self):
        t = good()
        t["events"][5] = {"seq":5,"kind":"terminate","actor":"alice"}
        self.assert_invalid(t)

    def test_no_events_after_termination(self):
        t = good()
        t["events"].append({"seq":8,"kind":"send","actor":"alice","target":"bob","message_id":"m2"})
        self.assert_invalid(t)

    def test_duplicate_actor_identity(self):
        t = good()
        t["events"][1]["actor"] = "alice"
        self.assert_invalid(t)

    def test_noncontiguous_sequence(self):
        t = good()
        t["events"][4]["seq"] = 7
        self.assert_invalid(t)

    def test_schema_rejects_unrecognized_events(self):
        t = good()
        t["events"][3]["kind"] = "memory_teleport"
        self.assert_invalid(t)

    def test_kind_cannot_smuggle_extra_fields(self):
        t = good()
        t["events"][1]["resource"] = "account"
        self.assert_invalid(t)

    def test_duplicate_message_id(self):
        t = good()
        t["events"].insert(5, {"kind":"send","actor":"alice","target":"bob","message_id":"m1"})
        for i,e in enumerate(t["events"]): e["seq"] = i
        self.assert_invalid(t)

if __name__ == "__main__":
    unittest.main()

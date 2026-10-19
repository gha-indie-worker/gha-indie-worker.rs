#!/usr/bin/env python3
"""Independent SOURCE-FREE reference model: no Oreslang compiler/publisher checkout."""
import base64
import hashlib
import unittest

CONTROL_FILES = ("policy/allowlist.txt", "workflows/carrier.yml", "scripts/publisher.py")
REVIEWER = "@security-reviewer"


class Denied(Exception):
    pass


def canonical_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def synthetic_content_payload(data, *, sha=None, size=None, encoding="base64"):
    return {
        "type": "file", "encoding": encoding,
        "size": len(data) if size is None else size,
        "content": base64.b64encode(data).decode("ascii"),
        "sha": sha if sha is not None else canonical_blob(data),
    }


def verify_effective_ownership(payload):
    """Minimal independently authored CODEOWNERS oracle, never used to publish."""
    if (not isinstance(payload, dict) or payload.get("type") != "file"
            or payload.get("encoding") != "base64"):
        raise Denied("malformed ownership metadata")
    raw64 = payload.get("content")
    size = payload.get("size")
    if (not isinstance(raw64, str) or len(raw64) > 24000
            or type(size) is not int or not 0 < size <= 16384):
        raise Denied("invalid ownership size")
    try:
        raw = base64.b64decode("".join(raw64.split()), validate=True)
        text = raw.decode("utf-8")
    except (ValueError, UnicodeError):
        raise Denied("invalid ownership encoding")
    if len(raw) != size or canonical_blob(raw) != payload.get("sha"):
        raise Denied("ownership digest mismatch")
    assignments = []
    for line in text.splitlines():
        fields = line.split()
        if not fields or line.lstrip().startswith("#"):
            continue
        if len(fields) != 2 or not fields[0].startswith("/") and fields[0] != "*":
            raise Denied("unsupported ownership rule")
        if any(c in fields[0] for c in "?[]!") or (
                "*" in fields[0] and fields[0] != "*"):
            raise Denied("unsupported ownership rule")
        assignments.append((fields[0], fields[1]))
    for path in CONTROL_FILES:
        effective = [owner for pattern, owner in assignments
                     if pattern == "*" or pattern == "/" + path
                     or pattern.endswith("/") and path.startswith(pattern[1:])]
        if not effective or effective[-1] != REVIEWER:
            raise Denied("missing effective owner")


def protection_ok(controls):
    if not isinstance(controls, dict) or controls.get("protected") is not True:
        raise Denied("branch not protected")
    if controls.get("admin_enforced") is not True:
        raise Denied("admin bypass")
    if controls.get("reviewer_required") is not True:
        raise Denied("CODEOWNER review not enforced")
    if controls.get("stale_review_dismissal") is not True:
        raise Denied("stale reviews accepted")
    if controls.get("force_push") is not False or controls.get("deletion") is not False:
        raise Denied("unsafe history mutation")
    if controls.get("review_bypass") != []:
        raise Denied("bypass not empty")


class Snapshot:
    def __init__(self):
        self.source = dict(protected=True, admin_enforced=True,
                           reviewer_required=True, stale_review_dismissal=True,
                           force_push=False, deletion=False, review_bypass=[])
        self.destination = dict(self.source)
        self.owner_bytes = b"".join(
            ("/" + file + " " + REVIEWER + "\n").encode() for file in CONTROL_FILES)
        self.owner = synthetic_content_payload(self.owner_bytes)
        self.allowlisted = True
        self.source_tree = "a" * 40
        self.expected_tree = "a" * 40
        self.destination_private = True
        self.writes = 0

    def authorize(self):
        protection_ok(self.source)
        protection_ok(self.destination)
        verify_effective_ownership(self.owner)
        if not self.allowlisted or not self.destination_private:
            raise Denied("destination not approved/private")
        if self.source_tree != self.expected_tree:
            raise Denied("source tree changed")

    def upload_one(self):
        self.authorize()
        self.writes += 1


class SourceFreeAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.s = Snapshot()

    def deny_without_writes(self):
        with self.assertRaises(Denied):
            self.s.upload_one()
        self.assertEqual(self.s.writes, 0)

    def test_clean_contract(self):
        self.s.upload_one()
        self.assertEqual(self.s.writes, 1)

    def test_source_unprotected(self):
        self.s.source["protected"] = False
        self.deny_without_writes()

    def test_destination_unprotected(self):
        self.s.destination["protected"] = False
        self.deny_without_writes()

    def test_admin_bypass(self):
        self.s.source["admin_enforced"] = False
        self.deny_without_writes()

    def test_missing_codeowner_enforcement(self):
        self.s.destination["reviewer_required"] = False
        self.deny_without_writes()

    def test_stale_approvals(self):
        self.s.source["stale_review_dismissal"] = False
        self.deny_without_writes()

    def test_force_push(self):
        self.s.source["force_push"] = True
        self.deny_without_writes()

    def test_deletion(self):
        self.s.destination["deletion"] = True
        self.deny_without_writes()

    def test_bypass_grants(self):
        self.s.destination["review_bypass"] = ["actor"]
        self.deny_without_writes()

    def test_missing_owner(self):
        self.s.owner = synthetic_content_payload(
            self.s.owner_bytes.replace(b"/scripts/publisher.py " +
                                       REVIEWER.encode() + b"\n", b""))
        self.deny_without_writes()

    def test_owner_override(self):
        self.s.owner = synthetic_content_payload(
            self.s.owner_bytes + b"/scripts/publisher.py @attacker\n")
        self.deny_without_writes()

    def test_invalid_owner_digest(self):
        self.s.owner["sha"] = "0" * 40
        self.deny_without_writes()

    def test_invalid_owner_size(self):
        self.s.owner["size"] = 0
        self.deny_without_writes()

    def test_invalid_owner_base64(self):
        self.s.owner["content"] = "# not base64"
        self.deny_without_writes()

    def test_owner_unsupported_glob(self):
        self.s.owner = synthetic_content_payload(
            self.s.owner_bytes + b"/scripts/* @attacker\n")
        self.deny_without_writes()

    def test_unapproved_destination(self):
        self.s.allowlisted = False
        self.deny_without_writes()

    def test_public_destination(self):
        self.s.destination_private = False
        self.deny_without_writes()

    def test_wrong_source_tree(self):
        self.s.source_tree = "b" * 40
        self.deny_without_writes()

    def test_owner_revoke_between_blobs(self):
        self.s.upload_one()
        self.s.owner = synthetic_content_payload(
            self.s.owner_bytes + b"/scripts/publisher.py @attacker\n")
        with self.assertRaises(Denied):
            self.s.upload_one()
        self.assertEqual(self.s.writes, 1)

    def test_source_protection_revoke_between_blobs(self):
        self.s.upload_one()
        self.s.source["protected"] = False
        with self.assertRaises(Denied):
            self.s.upload_one()
        self.assertEqual(self.s.writes, 1)

    def test_destination_protection_revoke_between_blobs(self):
        self.s.upload_one()
        self.s.destination["protected"] = False
        with self.assertRaises(Denied):
            self.s.upload_one()
        self.assertEqual(self.s.writes, 1)

    def test_policy_revoke_between_blobs(self):
        self.s.upload_one()
        self.s.allowlisted = False
        with self.assertRaises(Denied):
            self.s.upload_one()
        self.assertEqual(self.s.writes, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)

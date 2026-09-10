from native_fleet_test_support import *  # noqa: F401,F403

import base64
import copy
import hashlib
import hmac
import json

from tools.native_fleet_persistence import seal_checkpoint_state
from tools.native_fleet_validation import canonical_bytes

INTEGRITY_KEY_ID = "lab-checkpoint-key-v1"
INTEGRITY_KEY = b"checkpoint-integrity-key-material-v1!!"


class NativeFleetPersistenceTests(NativeFleetTestCase):
    def active_linux_lease(self, request_id: str = "req-checkpoint"):
        credential, _, _, _ = self.enroll_and_advertise(
            host_id="linux-lab-01",
            platform="linux",
            architecture="x64",
            trust_tier="public-trusted",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        request = dispatch_request(
            request_id=request_id,
            platform="linux",
            architecture="x64",
            profile="linux-rust",
            profile_digest=LINUX_DIGEST,
            capabilities=["cargo-cache"],
        )
        assigned = self.fleet.schedule(
            request, required_trust_tier="public-trusted", now=NOW
        )
        self.assertIsNotNone(assigned.lease)
        return credential, request, assigned.lease

    def restore(self, checkpoint, credential, *, now=NOW + timedelta(seconds=10)):
        return NativeFleet.restore_checkpoint(
            checkpoint,
            integrity_keys={INTEGRITY_KEY_ID: INTEGRITY_KEY},
            identity_secrets={credential.key_id: credential.secret},
            now=now,
        )

    def test_checkpoint_round_trip_preserves_active_and_terminal_replay(self) -> None:
        credential, request, lease = self.active_linux_lease()
        checkpoint = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
        )
        restored = self.restore(checkpoint, credential)
        duplicate = restored.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(seconds=10),
        )
        self.assertTrue(duplicate.duplicate)
        self.assertEqual(duplicate.lease.lease_id, lease.lease_id)

        renewed = restored.renew_lease(
            credential,
            lease_id=lease.lease_id,
            nonce=lease.nonce,
            now=NOW + timedelta(seconds=20),
        )
        receipt = restored.complete(
            credential,
            lease_id=renewed.lease_id,
            nonce=renewed.nonce,
            status="success",
            run_manifest_digest=MANIFEST_DIGEST,
            now=NOW + timedelta(seconds=30),
        )
        terminal_checkpoint = restored.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW + timedelta(seconds=30),
        )
        restored_again = self.restore(
            terminal_checkpoint,
            credential,
            now=NOW + timedelta(seconds=40),
        )
        terminal_duplicate = restored_again.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(seconds=40),
        )
        self.assertTrue(terminal_duplicate.duplicate)
        self.assertIsNone(terminal_duplicate.lease)
        self.assertEqual(terminal_duplicate.terminal_receipt, receipt)

    def test_checkpoint_is_deterministic_and_contains_no_identity_secret(self) -> None:
        credential, _, _ = self.active_linux_lease("req-secret-free")
        first = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
        )
        second = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
        )
        self.assertEqual(first, second)
        serialized = json.dumps(first, sort_keys=True)
        self.assertNotIn(credential.secret.hex(), serialized)
        self.assertNotIn(
            base64.urlsafe_b64encode(credential.secret).decode().rstrip("="), serialized
        )
        identity = first["state"]["identities"][0]
        self.assertEqual(set(identity), {"hostId", "keyId", "expiresAt", "revoked"})

    def test_restore_rejects_tampering_wrong_key_and_missing_identity_secret(self) -> None:
        credential, _, _ = self.active_linux_lease("req-tamper")
        checkpoint = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
        )
        tampered = copy.deepcopy(checkpoint)
        tampered["state"]["hosts"][0]["state"] = "offline"
        with self.assertRaisesRegex(FleetError, "checkpoint_digest_mismatch"):
            self.restore(tampered, credential)

        with self.assertRaisesRegex(FleetError, "checkpoint_signature_invalid"):
            NativeFleet.restore_checkpoint(
                checkpoint,
                integrity_keys={INTEGRITY_KEY_ID: b"wrong-integrity-key-material-32bytes"},
                identity_secrets={credential.key_id: credential.secret},
                now=NOW + timedelta(seconds=10),
            )

        with self.assertRaisesRegex(FleetError, "identity_secret_missing"):
            NativeFleet.restore_checkpoint(
                checkpoint,
                integrity_keys={INTEGRITY_KEY_ID: INTEGRITY_KEY},
                identity_secrets={},
                now=NOW + timedelta(seconds=10),
            )

    def test_signed_but_inconsistent_cross_references_fail_closed(self) -> None:
        credential, _, _ = self.active_linux_lease("req-reference")
        checkpoint = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
        )
        state = copy.deepcopy(checkpoint["state"])
        state["requestToLease"][0]["leaseId"] = "lease_missing"
        inconsistent = seal_checkpoint_state(
            state,
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            created_at=NOW,
        )
        with self.assertRaisesRegex(FleetError, "checkpoint_reference_invalid"):
            self.restore(inconsistent, credential)

        state = copy.deepcopy(checkpoint["state"])
        state["unknownField"] = True
        unknown = seal_checkpoint_state(
            state,
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            created_at=NOW,
        )
        with self.assertRaisesRegex(FleetError, "field_unknown"):
            self.restore(unknown, credential)

    def test_restore_sweeps_stale_active_authority_to_terminal_receipt(self) -> None:
        credential, request, lease = self.active_linux_lease("req-restart-expired")
        checkpoint = self.fleet.checkpoint(
            integrity_key_id=INTEGRITY_KEY_ID,
            integrity_key=INTEGRITY_KEY,
            now=NOW,
            sweep=False,
        )
        restored = self.restore(
            checkpoint,
            credential,
            now=NOW + timedelta(minutes=3),
        )
        self.assertEqual(restored.hosts["linux-lab-01"].state, "offline")
        receipt = restored.receipts_by_request[request["requestId"]]
        self.assertEqual(receipt.lease_id, lease.lease_id)
        self.assertEqual(receipt.status, "host-lost")
        replay = restored.schedule(
            request,
            required_trust_tier="public-trusted",
            now=NOW + timedelta(minutes=3),
        )
        self.assertTrue(replay.duplicate)
        self.assertEqual(replay.terminal_receipt, receipt)

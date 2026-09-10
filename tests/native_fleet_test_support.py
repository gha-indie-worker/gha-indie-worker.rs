from __future__ import annotations

import copy
import unittest
from datetime import datetime, timedelta, timezone

from tools.native_fleet_protocol import (
    FleetError,
    NativeFleet,
    capability_payload,
    dispatch_request,
    sha256_digest,
    sign_capability_envelope,
)

NOW = datetime(2026, 8, 8, 4, 0, tzinfo=timezone.utc)
MAC_DIGEST = "sha256:" + "1" * 64
WINDOWS_DIGEST = "sha256:" + "2" * 64
LINUX_DIGEST = "sha256:" + "3" * 64
MANIFEST_DIGEST = "sha256:" + "f" * 64




class NativeFleetTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.fleet = NativeFleet()

    def enroll_and_advertise(
        self,
        *,
        host_id: str,
        platform: str,
        architecture: str,
        trust_tier: str,
        profile: str,
        profile_digest: str,
        capabilities: list[str],
        concurrency: int = 1,
        now: datetime = NOW,
    ):
        token = self.fleet.issue_bootstrap(
            host_id=host_id,
            platform=platform,
            architecture=architecture,
            trust_tier=trust_tier,
            now=now,
        )
        credential = self.fleet.enroll(token, host_id=host_id, now=now)
        payload = capability_payload(
            host_id=host_id,
            platform=platform,
            architecture=architecture,
            trust_tier=trust_tier,
            profiles=[(profile, profile_digest)],
            capabilities=capabilities,
            concurrency=concurrency,
        )
        envelope = sign_capability_envelope(
            credential,
            payload,
            now,
            now + timedelta(minutes=5),
        )
        digest = self.fleet.advertise(envelope, now=now)
        return credential, payload, digest, token

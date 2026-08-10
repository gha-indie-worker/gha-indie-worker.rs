#!/usr/bin/env python3
"""Tamper-evident, secret-free checkpoint and restore for native-fleet lab state.

The checkpoint deliberately excludes simulator identity secrets. Restores receive
those values from a separate resolver so state storage never becomes a credential
store. Production storage must add managed keys, transactional persistence, and
platform-bound public-key identities.
"""

from .native_fleet_checkpoint_common import (
    CHECKPOINT_SCHEMA,
    CHECKPOINT_STATE_SCHEMA,
    seal_checkpoint_state,
)
from .native_fleet_checkpoint_snapshot import CheckpointSnapshotMixin
from .native_fleet_checkpoint_restore import CheckpointRestoreMixin
from .native_fleet_checkpoint_references import CheckpointReferenceValidationMixin


class PersistenceMixin(
    CheckpointSnapshotMixin,
    CheckpointRestoreMixin,
    CheckpointReferenceValidationMixin,
):
    """Secret-free checkpoint and fail-closed restore operations."""

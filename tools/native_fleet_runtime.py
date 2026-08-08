#!/usr/bin/env python3
"""Composed in-memory native fleet protocol simulator."""

from .native_fleet_base import FleetBase
from .native_fleet_enrollment import EnrollmentMixin
from .native_fleet_lifecycle import LifecycleMixin
from .native_fleet_scheduling import SchedulingMixin


class NativeFleet(EnrollmentMixin, SchedulingMixin, LifecycleMixin, FleetBase):
    """Exact-match native fleet state machine with exclusive leases."""


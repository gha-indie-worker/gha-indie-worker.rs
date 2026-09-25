#!/usr/bin/env python3
from itertools import permutations

# Exhaust every two-worker claim ordering and compare with the serial specification.
def claim(owner, worker):
    return worker if owner is None else owner

serial_outcomes = set()
for order in permutations(("worker_a", "worker_b")):
    owner = None
    for worker in order:
        owner = claim(owner, worker)
    serial_outcomes.add(owner)
    assert owner == order[0], "later claimant displaced the linearized owner"
assert serial_outcomes == {"worker_a", "worker_b"}
# Replaying the winner's claim is idempotent.
for winner in serial_outcomes:
    assert claim(winner, winner) == winner
print("worker claim linearizability model: ok")

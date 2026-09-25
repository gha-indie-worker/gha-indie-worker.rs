#!/usr/bin/env python3
from itertools import permutations

WORKERS = ("worker_a", "worker_b")
EVENTS = tuple((w, phase) for w in WORKERS for phase in ("read", "cas"))


def preserves_program_order(order):
    pos = {event: i for i, event in enumerate(order)}
    return all(pos[(w, "read")] < pos[(w, "cas")] for w in WORKERS)


histories = 0
concurrent_histories = 0
for order in permutations(EVENTS):
    if not preserves_program_order(order):
        continue
    histories += 1
    owner = None
    observed = {}
    success = {}
    for worker, phase in order:
        if phase == "read":
            observed[worker] = owner
            continue
        expected = observed[worker]
        won = owner == expected and expected is None
        if won:
            owner = worker
        success[worker] = won

    assert owner in WORKERS, "a complete claim history ended without an owner"
    assert sum(success.values()) == 1, "claim history produced zero or multiple winners"
    winner = next(w for w, won in success.items() if won)
    assert owner == winner, "final owner differs from the successful CAS claimant"

    # If one operation completed before the other even read, real-time order fixes
    # the linearization order. Otherwise the overlapping history may linearize to
    # whichever CAS succeeded first.
    pos = {event: i for i, event in enumerate(order)}
    for first, second in (("worker_a", "worker_b"), ("worker_b", "worker_a")):
        if pos[(first, "cas")] < pos[(second, "read")]:
            assert winner == first, "real-time precedence violated linearizability"
    if not (
        pos[("worker_a", "cas")] < pos[("worker_b", "read")]
        or pos[("worker_b", "cas")] < pos[("worker_a", "read")]
    ):
        concurrent_histories += 1

assert histories == 6, f"unexpected history count: {histories}"
assert concurrent_histories > 0, "no overlapping claim histories were explored"
print("worker claim interleaving linearizability model: ok")

#!/usr/bin/env python3
"""Source-free independent iterative type-tree budget model.

No Oreslang compiler code or contract files are transferred into this repo.
"""
from dataclasses import dataclass
import unittest


@dataclass(frozen=True)
class TypeNode:
    name: str
    children: tuple = ()


class LimitExceeded(ValueError):
    pass


def bounded_tree(roots, *, max_roots=1024, max_nodes=8192, max_depth=64):
    if len(roots) > max_roots:
        raise LimitExceeded("too many formals")
    pending = [(root, 0) for root in roots]
    visited = 0
    peak_frontier = len(pending)
    while pending:
        current, depth = pending.pop()
        visited += 1
        if visited > max_nodes or depth > max_depth:
            raise LimitExceeded("node/depth limit")
        children = current.children
        # Frontier preflight MUST precede any append.
        if visited + len(pending) + len(children) > max_nodes:
            raise LimitExceeded("node frontier limit")
        pending.extend((child, depth + 1) for child in children)
        peak_frontier = max(peak_frontier, len(pending))
    return visited, peak_frontier


class BoundedFingerprintTypeTests(unittest.TestCase):
    def test_shallow_scalar_tree(self):
        self.assertEqual(bounded_tree([TypeNode("int")]), (1, 1))

    def test_wide_tree_fails_without_frontier_explosion(self):
        leaf = TypeNode("int")
        root = TypeNode("Vector", (leaf,) * 50_000)
        with self.assertRaises(LimitExceeded):
            bounded_tree([root])

    def test_exact_node_budget_is_admitted(self):
        leaf = TypeNode("int")
        root = TypeNode("Tree", (leaf,) * 8191)
        visited, frontier = bounded_tree([root])
        self.assertEqual(visited, 8192)
        self.assertEqual(frontier, 8191)

    def test_one_node_beyond_budget_is_rejected(self):
        leaf = TypeNode("int")
        with self.assertRaises(LimitExceeded):
            bounded_tree([TypeNode("Tree", (leaf,) * 8192)])

    def test_depth_64_accepted_depth_65_rejected(self):
        leaf = TypeNode("int")
        for _ in range(64):
            leaf = TypeNode("Wrapper", (leaf,))
        self.assertEqual(bounded_tree([leaf])[0], 65)
        leaf = TypeNode("Wrapper", (leaf,))
        with self.assertRaises(LimitExceeded):
            bounded_tree([leaf])

    def test_many_roots_rejected_before_any_enqueues(self):
        with self.assertRaises(LimitExceeded):
            bounded_tree([TypeNode("int")] * 1025)

    def test_global_frontier_budget_counts_siblings(self):
        root = TypeNode("R", (TypeNode("V", (TypeNode("int"),) * 5000),
                              TypeNode("S", (TypeNode("int"),) * 4000)))
        with self.assertRaises(LimitExceeded):
            bounded_tree([root])

    def test_zero_parameter_contract(self):
        self.assertEqual(bounded_tree([]), (0, 0))



def validate_option_shape_flag(type_name, type_args, inferred, claimed):
    # Synthetic shape consistency only. Actual nominal identity is separately
    # resolved by a compiler; this helper intentionally never grants it.
    is_option_shape = type_name == "Option" and len(type_args) == 1 and not inferred
    if is_option_shape != claimed:
        raise ValueError("inconsistent Option identity claim")
    return is_option_shape


class OptionProofShapeTests(unittest.TestCase):
    def test_fake_option_claim_on_plain_integer_rejected(self):
        with self.assertRaises(ValueError):
            validate_option_shape_flag("int", (), False, True)

    def test_missing_claim_on_option_t_rejected(self):
        with self.assertRaises(ValueError):
            validate_option_shape_flag("Option", ("int",), False, False)

    def test_valid_option_shape_still_not_nominal_proof(self):
        self.assertTrue(validate_option_shape_flag(
            "Option", ("int",), False, True))

    def test_regular_scalar_does_not_require_option_proof(self):
        self.assertFalse(validate_option_shape_flag("int", (), False, False))

if __name__ == "__main__":
    unittest.main(verbosity=2)

"""Deterministic independent address-enumeration oracle for writable NdArray views.

This verifies conservative reference acceptance at small dimensions; it does
not replace a compiler alias analysis or authorize native pointer access.
"""
import itertools
import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from verify_ndarray_layout import LayoutError, validate_unique_write_view, validate_view


class NonAliasingPropertyTests(unittest.TestCase):
    def test_accepted_writers_never_address_one_element_twice(self):
        rng = random.Random(419)
        admitted = 0
        rejected = 0
        for _ in range(6000):
            rank = rng.randint(0, 4)
            shape = tuple(rng.randint(0, 3) for _ in range(rank))
            strides = tuple(rng.randint(-4, 4) * 8 for _ in shape)
            offset = rng.randint(0, 20) * 8
            storage = 256
            try:
                lo, hi = validate_unique_write_view(shape, strides, offset,
                                                    storage, 8)
            except LayoutError:
                rejected += 1
                continue
            admitted += 1
            self.assertGreaterEqual(lo, 0)
            self.assertLessEqual(hi, storage)
            locations = [
                offset + sum(i * stride for i, stride in zip(coords, strides))
                for coords in itertools.product(*(range(dim) for dim in shape))
            ]
            self.assertEqual(len(locations), len(set(locations)),
                             (shape, strides, offset))
            for location in locations:
                self.assertGreaterEqual(location, lo)
                self.assertLessEqual(location + 8, hi)
        self.assertGreater(admitted, 100)
        self.assertGreater(rejected, 100)

    def test_readonly_may_broadcast_while_mutable_must_not(self):
        self.assertEqual(validate_view((4, 2), (0, 8), 0, 16, 8), (0, 16))
        with self.assertRaises(LayoutError):
            validate_unique_write_view((4, 2), (0, 8), 0, 16, 8)


if __name__ == "__main__":
    unittest.main()

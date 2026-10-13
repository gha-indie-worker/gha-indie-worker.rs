#!/usr/bin/env python3
import sys
from pathlib import Path
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parent))
import metamorphic

class MetamorphicProof(unittest.TestCase):
    def test_seeded_generation_is_reproducible(self):
        a = metamorphic.catalog(20261008, 32)
        b = metamorphic.catalog(20261008, 32)
        c = metamorphic.catalog(20261009, 32)
        self.assertEqual(a, b)
        self.assertNotEqual(a[1], c[1])

    def test_distinct_source_forms_share_declared_safe_result(self):
        for seed in (0, 1, 31, 20261008):
            for index in range(128):
                left, right, expected = metamorphic.generate(seed, index)
                self.assertNotEqual(left, right)
                self.assertTrue(0 <= expected <= 119)
                self.assertIn("pub fnc main(): int { return", left)
                self.assertTrue(left.endswith("; }\n"))
                self.assertTrue(right.endswith("; }\n"))

    def test_missing_compiler_is_failure_not_skip(self):
        from tempfile import TemporaryDirectory
        with TemporaryDirectory() as d:
            with patch.object(metamorphic.subprocess, "run", side_effect=FileNotFoundError("compiler missing")):
                with self.assertRaises(FileNotFoundError):
                    metamorphic.execute("pub fnc main(): int { return 2; }\n",
                                        ["definitely-missing", "{source}"], "clang", 2, d)

if __name__ == "__main__":
    unittest.main()

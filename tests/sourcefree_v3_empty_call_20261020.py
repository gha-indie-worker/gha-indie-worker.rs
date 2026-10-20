#!/usr/bin/env python3
"""Independent synthetic function-arity/default model, no private source."""
import unittest
from dataclasses import dataclass


class MissingRequired(ValueError):
    pass


class _Absent:
    pass


ABSENT = _Absent()


@dataclass(frozen=True)
class Formal:
    name: str
    required: bool = True
    default: object = ABSENT
    option: bool = False


@dataclass(frozen=True)
class Some:
    value: object


@dataclass(frozen=True)
class NoneOption:
    pass


def normalize_closed_positional(formals, actuals):
    """Represent lexical plain values then map to verified positional formals."""
    if len({f.name for f in formals}) != len(formals):
        raise ValueError("duplicate parameter")
    if len(actuals) > len(formals):
        raise ValueError("too many values")
    if any(callable(value) for value in actuals):
        raise ValueError("unsupported effectful source")
    if not formals and not actuals:
        return ()
    out = []
    for i, formal in enumerate(formals):
        if i < len(actuals):
            raw = actuals[i]
            if formal.option:
                out.append(raw if isinstance(raw, (Some, NoneOption)) else Some(raw))
            else:
                out.append(raw)
        elif formal.default is not ABSENT:
            val = formal.default
            out.append(Some(val) if formal.option and not isinstance(val, NoneOption) else val)
        elif formal.option and not formal.required:
            out.append(NoneOption())
        else:
            raise MissingRequired(formal.name)
    return tuple(out)


class EmptyCallTests(unittest.TestCase):
    def test_zero_formal_call_has_zero_values(self):
        self.assertEqual(normalize_closed_positional([], []), ())

    def test_defaulted_scalar_empty_call_injects_default(self):
        f = [Formal("v", default=7)]
        self.assertEqual(normalize_closed_positional(f, []), (7,))

    def test_defaulted_option_empty_call_wraps_some(self):
        f = [Formal("v", default=7, option=True)]
        self.assertEqual(normalize_closed_positional(f, []), (Some(7),))

    def test_optional_option_empty_call_injects_none(self):
        f = [Formal("v", required=False, option=True)]
        self.assertEqual(normalize_closed_positional(f, []), (NoneOption(),))

    def test_supplied_explicit_none_wins_over_default(self):
        f = [Formal("v", default=7, option=True)]
        supplied = NoneOption()
        self.assertIs(normalize_closed_positional(f, [supplied])[0], supplied)

    def test_plain_positional_value(self):
        self.assertEqual(normalize_closed_positional([Formal("v")], [9]), (9,))

    def test_partial_positional_prefix_and_default(self):
        f = [Formal("a"), Formal("b", default=5)]
        self.assertEqual(normalize_closed_positional(f, [8]), (8, 5))

    def test_required_absent_rejected(self):
        with self.assertRaises(MissingRequired):
            normalize_closed_positional([Formal("v")], [])

    def test_excess_values_rejected(self):
        with self.assertRaises(ValueError):
            normalize_closed_positional([Formal("v")], [1, 2])

    def test_effectful_source_is_not_admitted(self):
        with self.assertRaises(ValueError):
            normalize_closed_positional([Formal("v")], [lambda: 5])

    def test_duplicate_formals_rejected(self):
        with self.assertRaises(ValueError):
            normalize_closed_positional([Formal("v"), Formal("v")], [1, 2])

    def test_multi_omit_with_required_middle_is_rejected(self):
        with self.assertRaises(MissingRequired):
            normalize_closed_positional([
                Formal("a", default=1), Formal("b"), Formal("c", default=3)], [])


if __name__ == "__main__":
    unittest.main(verbosity=2)

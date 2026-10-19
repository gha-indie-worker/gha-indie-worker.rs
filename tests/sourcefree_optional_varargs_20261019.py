#!/usr/bin/env python3
"""Independent synthetic optional/rest call-binding oracle. No Oreslang repository code."""
import random
import unittest
from dataclasses import dataclass


class InvalidCall(ValueError):
    pass


@dataclass(frozen=True)
class Formal:
    name: str
    optional: bool = False
    rest: bool = False


@dataclass(frozen=True)
class Actual:
    kind: str = "value"     # value | none | omit
    label: str | None = None


def bind(formals, actuals):
    if len({f.name for f in formals}) != len(formals):
        raise InvalidCall("duplicate formals")
    rest = len(formals) - 1 if formals and formals[-1].rest else -1
    if any(f.rest and (i != rest or f.optional) for i, f in enumerate(formals)):
        raise InvalidCall("invalid rest declaration")
    by_name = {f.name: i for i, f in enumerate(formals)}
    fixed = len(formals) if rest < 0 else rest
    supplied = {}
    explicit = []
    original_by_evaluated = []
    rest_sources = []
    after_named = False
    pos = 0
    for raw, a in enumerate(actuals):
        if a.kind not in ("value", "none", "omit"):
            raise InvalidCall("bad source item")
        if a.label is not None:
            after_named = True
            if a.label not in by_name:
                raise InvalidCall("unknown label")
            slot = by_name[a.label]
        else:
            if after_named:
                raise InvalidCall("positional after named")
            slot = pos
            pos += 1
        if slot >= fixed:
            if slot != rest and rest < 0:
                raise InvalidCall("extra positional")
            if a.label is not None:
                raise InvalidCall("named rest pack unsupported")
            if a.kind == "omit":
                raise InvalidCall("rest omitted")
            rest_sources.append(len(original_by_evaluated))
            original_by_evaluated.append(raw)
            continue
        if slot in supplied:
            raise InvalidCall("duplicate slot")
        if a.kind == "omit":
            if not formals[slot].optional:
                raise InvalidCall("required omitted")
            supplied[slot] = ("omit", None)
            explicit.append((raw, slot))
        else:
            supplied[slot] = (a.kind, len(original_by_evaluated))
            original_by_evaluated.append(raw)
    for i in range(fixed):
        if i not in supplied and not formals[i].optional:
            raise InvalidCall("required missing")
    if rest_sources and any(i not in supplied for i in range(fixed)):
        raise InvalidCall("rest needs fixed prefix")
    source_by_formal = [
        supplied[i][1] if i in supplied and supplied[i][0] != "omit" else -1
        for i in range(fixed)
    ]
    statuses = [
        "implicit_omit" if i not in supplied else
        "explicit_omit" if supplied[i][0] == "omit" else
        "explicit_none" if supplied[i][0] == "none" else "provided"
        for i in range(fixed)
    ]
    return {
        "fixed": tuple(source_by_formal),
        "statuses": tuple(statuses),
        "rest": tuple(rest_sources),
        "eval_raw": tuple(original_by_evaluated),
        "explicit": tuple(explicit),
    }


def audit(plan, formals, actuals):
    count = len(plan["eval_raw"])
    assert len(set(plan["eval_raw"])) == count
    assert list(plan["eval_raw"]) == sorted(plan["eval_raw"])
    assert all(0 <= i < len(actuals) for i in plan["eval_raw"])
    omitted_raw = sorted(i for i, a in enumerate(actuals) if a.kind == "omit")
    assert sorted(pair[0] for pair in plan["explicit"]) == omitted_raw
    assert count + len(omitted_raw) == len(actuals)
    destinations = [x for x in plan["fixed"] if x >= 0] + list(plan["rest"])
    assert sorted(destinations) == list(range(count))
    assert list(plan["rest"]) == sorted(plan["rest"])
    if plan["rest"]:
        assert max([x for x in plan["fixed"] if x >= 0] or [-1]) < min(plan["rest"])
        assert all(p in plan["explicit"] for p in [
            (raw, slot) for raw, slot in plan["explicit"]])
    for raw, slot in plan["explicit"]:
        assert plan["statuses"][slot] == "explicit_omit"
        assert actuals[raw].kind == "omit"
        assert formals[slot].optional
    for slot, status in enumerate(plan["statuses"]):
        if status == "explicit_none":
            assert plan["fixed"][slot] >= 0
            assert actuals[plan["eval_raw"][plan["fixed"][slot]]].kind == "none"
        if status == "implicit_omit":
            assert plan["fixed"][slot] == -1
    return True


class SourceFreeOptionalVarargAudit(unittest.TestCase):
    def setUp(self):
        self.f = [Formal("a"), Formal("b", optional=True), Formal("c"), Formal("tags", rest=True)]

    def test_explicit_none_is_supplied(self):
        got = bind(self.f, [Actual(), Actual("none"), Actual()])
        self.assertEqual(got["statuses"], ("provided", "explicit_none", "provided"))
        self.assertEqual(got["eval_raw"], (0, 1, 2))
        self.assertTrue(audit(got, self.f, [Actual(), Actual("none"), Actual()]))

    def test_omit_consumes_slot_not_evaluation(self):
        args = [Actual(), Actual("omit"), Actual(), Actual(), Actual()]
        got = bind(self.f, args)
        self.assertEqual(got["statuses"], ("provided", "explicit_omit", "provided"))
        self.assertEqual(got["eval_raw"], (0, 2, 3, 4))
        self.assertEqual(got["rest"], (2, 3))
        self.assertEqual(got["explicit"], ((1, 1),))
        self.assertTrue(audit(got, self.f, args))

    def test_missing_middle_named(self):
        args = [Actual(label="c"), Actual(label="a")]
        got = bind(self.f, args)
        self.assertEqual(got["fixed"], (1, -1, 0))
        self.assertEqual(got["statuses"], ("provided", "implicit_omit", "provided"))
        self.assertTrue(audit(got, self.f, args))

    def test_named_explicit_omit(self):
        args = [Actual(label="c"), Actual("omit", label="b"), Actual(label="a")]
        got = bind(self.f, args)
        self.assertEqual(got["explicit"], ((1, 1),))
        self.assertEqual(got["eval_raw"], (0, 2))
        self.assertTrue(audit(got, self.f, args))

    def test_empty_rest_not_none(self):
        got = bind(self.f, [Actual(), Actual("none"), Actual()])
        self.assertEqual(got["rest"], ())

    def test_reject_missing_required(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(label="a")])

    def test_reject_omit_required(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual("omit"), Actual(), Actual()])

    def test_reject_omit_rest(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(), Actual("omit"), Actual(), Actual("omit")])

    def test_reject_positional_after_named(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(label="a"), Actual()])

    def test_reject_duplicate_slot(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(), Actual(label="a"), Actual(label="c")])

    def test_reject_unknown_label(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(label="z")])

    def test_reject_named_rest(self):
        with self.assertRaises(InvalidCall):
            bind(self.f, [Actual(label="a"), Actual(label="c"), Actual(label="tags")])

    def test_reject_malformed_formals(self):
        for formals in ([Formal("x", rest=True), Formal("a")],
                        [Formal("x", optional=True, rest=True)],
                        [Formal("x"), Formal("x")]):
            with self.subTest(formals=formals), self.assertRaises(InvalidCall):
                bind(formals, [])

    def test_randomized_provenance_and_rest_order(self):
        rng = random.Random(1062026)
        accepted = 0
        rejected = 0
        for _ in range(10000):
            args = []
            named = False
            for i in range(rng.randint(0, 9)):
                label = rng.choice(["a", "b", "c", "tags", "unknown", None])
                if rng.randrange(6) == 0:
                    named = True
                if not named:
                    label = None
                args.append(Actual(rng.choice(["value", "value", "none", "omit"]), label))
            try:
                p = bind(self.f, args)
                self.assertTrue(audit(p, self.f, args))
                accepted += 1
            except InvalidCall:
                rejected += 1
        self.assertGreater(accepted, 300)
        self.assertGreater(rejected, 3000)
        print("SOURCE_FREE_RANDOM_CASES=10000")
        print("SOURCE_FREE_ACCEPTED=" + str(accepted))
        print("SOURCE_FREE_REJECTED=" + str(rejected))


# Independent synthetic ABI assembly model: no code or types imported from
# Oreslang's private repositories. Values arrive after lexical evaluation.
@dataclass(frozen=True)
class SyntheticOption:
    has_value: bool
    value: object = None


def assemble_option_slots(slots, lexical_values):
    """slots: (action, evaluated-value index); -1 means omitted."""
    if any(v is None for v in lexical_values):
        raise InvalidCall("null/void is not a source value")
    used = [idx for action, idx in slots if idx >= 0]
    if sorted(used) != list(range(len(lexical_values))):
        raise InvalidCall("source values missing or duplicated")
    out = []
    for action, index in slots:
        if action == "inject_none" and index == -1:
            adapted = SyntheticOption(False)
        elif 0 <= index < len(lexical_values):
            raw = lexical_values[index]
            if action == "wrap_some":
                if isinstance(raw, SyntheticOption):
                    raise InvalidCall("unexpected nested Option")
                adapted = SyntheticOption(True, raw)
            elif action == "pass_option":
                if not isinstance(raw, SyntheticOption):
                    raise InvalidCall("forged supplied Option")
                adapted = raw
            elif action == "pass_required":
                adapted = raw
            else:
                raise InvalidCall("invalid adaptation")
        else:
            raise InvalidCall("invalid omission/action index")
        out.append(adapted)
    return tuple(out)


class SourceFreeOptionAdaptationAudit(unittest.TestCase):
    def test_named_value_permutation_preserves_temporaries(self):
        actual = assemble_option_slots([
            ("pass_required", 1), ("inject_none", -1), ("pass_required", 0)],
            [True, 3])
        self.assertEqual(actual, (3, SyntheticOption(False), True))

    def test_supplied_none_retains_identity_and_is_not_omission(self):
        supplied = SyntheticOption(False)
        actual = assemble_option_slots([
            ("pass_required", 0), ("pass_option", 1), ("pass_required", 2)],
            [3, supplied, True])
        self.assertIs(actual[1], supplied)

    def test_wrap_plain_value_into_exactly_one_some(self):
        self.assertEqual(
            assemble_option_slots([("wrap_some", 0)], ["hello"]),
            (SyntheticOption(True, "hello"),))

    def test_empty_rest_is_not_none(self):
        self.assertNotEqual((), (SyntheticOption(False),))

    def test_reject_java_null_or_void_like_nonvalue(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([("wrap_some", 0)], [None])

    def test_reject_fake_supplied_option(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([("pass_option", 0)], ["None"])

    def test_reject_double_wrapping_option(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([("wrap_some", 0)], [SyntheticOption(False)])

    def test_reject_duplicate_source_consumption(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([
                ("pass_required", 0), ("pass_required", 0)], [3])

    def test_reject_unconsumed_source_temp(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([("pass_required", 0)], [3, 4])

    def test_reject_missing_omitted_marker(self):
        with self.assertRaises(InvalidCall):
            assemble_option_slots([("pass_required", -1)], [])

if __name__ == "__main__":
    unittest.main(verbosity=2)

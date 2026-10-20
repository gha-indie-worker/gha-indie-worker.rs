#!/usr/bin/env python3
"""
Source-free behavioral oracle for Oreslang Future cancellation semantics.

This file intentionally contains no Oreslang source code, generated code, Git
objects, or copied implementation text. It models only externally stated
invariants:
  * a scheduler turn never blocks on a pending/unrevoked terminal;
  * cancellation is not safely observable until producer revocation completes;
  * host waiters remain blocked behind that revocation barrier;
  * zero-timeout polling reports unavailable while revocation is in flight;
  * an already usable terminal value is readable without suspension.
"""

import threading
import time
import unittest

PENDING = object()


class SchedulerWouldBlock(RuntimeError):
    pass


class PollTimeout(TimeoutError):
    pass


class Cancelled(RuntimeError):
    pass


class FutureOracle:
    def __init__(self):
        self._cv = threading.Condition()
        self._state = PENDING
        self._revocation_complete = False

    def complete(self, value):
        with self._cv:
            if self._state is not PENDING:
                return False
            self._state = ("ok", value)
            self._revocation_complete = True
            self._cv.notify_all()
            return True

    def cancel_with_revoker(self, revoker):
        with self._cv:
            if self._state is not PENDING:
                return False
            self._state = ("cancelled", None)
        # Deliberately outside the lock: cancellation identity is visible
        # before authority revocation, matching the race under test.
        revoker()
        with self._cv:
            self._revocation_complete = True
            self._cv.notify_all()
        return True

    def cancelled_flag(self):
        with self._cv:
            return self._state is not PENDING and self._state[0] == "cancelled"

    def usable_terminal(self):
        with self._cv:
            if self._state is PENDING:
                return None
            if self._state[0] == "cancelled" and not self._revocation_complete:
                return None
            return self._state

    def observe(self, *, scheduler_turn=False, timeout=None):
        with self._cv:
            def terminal():
                if self._state is PENDING:
                    return None
                if self._state[0] == "cancelled" and not self._revocation_complete:
                    return None
                return self._state

            current = terminal()
            if current is None and scheduler_turn:
                raise SchedulerWouldBlock("logical turn must suspend instead of parking")
            if current is None and timeout == 0:
                raise PollTimeout("no usable terminal state")

            deadline = None if timeout is None else time.monotonic() + timeout
            while current is None:
                if deadline is None:
                    self._cv.wait()
                else:
                    left = deadline - time.monotonic()
                    if left <= 0:
                        raise PollTimeout("no usable terminal state")
                    self._cv.wait(left)
                current = terminal()

            if current[0] == "cancelled":
                raise Cancelled("producer authority revoked")
            return current[1]


class FutureRevocationOracleTest(unittest.TestCase):
    def _blocked_cancel(self):
        f = FutureOracle()
        entered = threading.Event()
        release = threading.Event()
        result = {}

        def revoker():
            entered.set()
            if not release.wait(5):
                raise AssertionError("revoker release timeout")

        def cancel():
            try:
                result["won"] = f.cancel_with_revoker(revoker)
            except BaseException as exc:
                result["error"] = exc

        t = threading.Thread(target=cancel, daemon=True)
        t.start()
        self.assertTrue(entered.wait(5))
        self.assertTrue(f.cancelled_flag())
        self.assertIsNone(f.usable_terminal())
        return f, release, t, result

    def test_scheduler_rejects_pending_blocking_bridge(self):
        f = FutureOracle()
        with self.assertRaises(SchedulerWouldBlock):
            f.observe(scheduler_turn=True)
        self.assertTrue(f.complete(41))
        self.assertEqual(41, f.observe(scheduler_turn=True))

    def test_scheduler_rejects_cancelled_but_unrevoked_terminal(self):
        f, release, t, result = self._blocked_cancel()
        try:
            with self.assertRaises(SchedulerWouldBlock):
                f.observe(scheduler_turn=True)
            with self.assertRaises(PollTimeout):
                f.observe(timeout=0)
        finally:
            release.set()
            t.join(5)
        self.assertFalse(t.is_alive())
        self.assertNotIn("error", result)
        self.assertTrue(result.get("won"))
        with self.assertRaises(Cancelled):
            f.observe(scheduler_turn=True)

    def test_host_waiter_stays_behind_revocation_barrier(self):
        f, release, canceller, result = self._blocked_cancel()
        observer_done = threading.Event()
        observer = {}

        def observe():
            try:
                f.observe()
                observer["value"] = "unexpected"
            except Cancelled:
                observer["cancelled"] = True
            except BaseException as exc:
                observer["error"] = exc
            finally:
                observer_done.set()

        waiter = threading.Thread(target=observe, daemon=True)
        waiter.start()
        time.sleep(0.03)
        self.assertFalse(observer_done.is_set(), "host observer crossed live authority")
        release.set()
        canceller.join(5)
        waiter.join(5)
        self.assertFalse(canceller.is_alive())
        self.assertFalse(waiter.is_alive())
        self.assertNotIn("error", result)
        self.assertNotIn("error", observer)
        self.assertTrue(observer.get("cancelled"))

    def test_zero_timeout_poll_respects_revocation(self):
        f, release, t, result = self._blocked_cancel()
        try:
            with self.assertRaises(PollTimeout):
                f.observe(timeout=0)
        finally:
            release.set()
            t.join(5)
        self.assertNotIn("error", result)
        with self.assertRaises(Cancelled):
            f.observe(timeout=0)

    def test_race_stress(self):
        for _ in range(250):
            f = FutureOracle()
            entered = threading.Event()
            release = threading.Event()

            def revoker():
                entered.set()
                release.wait(2)

            t = threading.Thread(target=lambda: f.cancel_with_revoker(revoker), daemon=True)
            t.start()
            self.assertTrue(entered.wait(2))
            with self.assertRaises(SchedulerWouldBlock):
                f.observe(scheduler_turn=True)
            release.set()
            t.join(2)
            self.assertFalse(t.is_alive())
            with self.assertRaises(Cancelled):
                f.observe(scheduler_turn=True)


if __name__ == "__main__":
    unittest.main()

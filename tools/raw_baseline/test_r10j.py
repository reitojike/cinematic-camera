"""Synthetic tests for the round-10j helpers; no real photos."""
import unittest

import r10j_final as fj


class SpreadTests(unittest.TestCase):
    def test_fewer_candidates_are_all_kept_once(self):
        self.assertEqual(fj.spread(list(range(5)), 12), [0, 1, 2, 3, 4])

    def test_many_candidates_are_evenly_spaced_without_repeats(self):
        picked = fj.spread(list(range(30)), 12)
        self.assertEqual(len(picked), 12)
        self.assertEqual(len(set(picked)), 12)
        self.assertEqual(picked, sorted(picked))


if __name__ == "__main__":
    unittest.main()

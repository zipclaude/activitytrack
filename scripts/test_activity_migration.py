# SPDX-License-Identifier: GPL-3.0-or-later
# Runs docs/samples/activity-migration.json through scripts/activity_migration.py:
#   python3 -I -m unittest        (from scripts/)    or    python3 -I scripts/test_activity_migration.py

import copy
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from activity_migration import classify_ref, map_priority, migrate, normalize  # noqa: E402

CASES = os.path.join(HERE, "..", "docs", "samples", "activity-migration.json")


def canon(tables):
	return json.loads(json.dumps(tables, sort_keys=True))


class MigrationCases(unittest.TestCase):
	@classmethod
	def setUpClass(cls):
		with open(CASES) as f:
			cls.doc = json.load(f)

	def test_enough_cases(self):
		self.assertGreaterEqual(len(self.doc["cases"]), 20)

	def test_cases(self):
		for case in self.doc["cases"]:
			with self.subTest(case["name"]):
				before = copy.deepcopy(case["before"])
				got = normalize(migrate(before, case["zone"]))
				self.assertEqual(canon(got), canon(case["after"]))
				self.assertEqual(before, case["before"], "migrate must not modify its input")

	def test_deterministic_and_unique_uids(self):
		for case in self.doc["cases"]:
			a = migrate(case["before"], case["zone"])
			self.assertEqual(a, migrate(case["before"], case["zone"]))
			for table in ("time_entry", "external_ref"):
				uids = [r["uid"] for r in a[table]]
				self.assertEqual(len(uids), len(set(uids)), case["name"])

	def test_no_todo_left_and_invariants(self):
		for case in self.doc["cases"]:
			for e in migrate(case["before"], case["zone"])["time_entry"]:
				self.assertNotIn("todo_id", e)
				self.assertIn(e["status"], ("TODO", "RUNNING", "CLOSED"))
				self.assertTrue(0 <= e["priority"] <= 5)
				self.assertEqual(e["kind"] == "ABSENCE", e["absence_kind"] is not None)
				if e["kind"] == "ABSENCE":
					self.assertEqual(e["status"], "CLOSED")


class Helpers(unittest.TestCase):
	def test_priority(self):
		self.assertEqual([map_priority(p) for p in (None, 1, 2, 3, 4, 5, 6, 7, 0, -1)], [0, 5, 4, 3, 2, 1, 1, 1, 0, 0])

	def test_classify(self):
		self.assertEqual(classify_ref(" https://a.b/c "), "URL")
		self.assertEqual(classify_ref("{123e4567-e89b-12d3-a456-426614174000}"), "UUID")
		self.assertEqual(classify_ref("INC0012345"), "TICKET")
		self.assertEqual(classify_ref("hello world"), "OTHER")


if __name__ == "__main__":
	unittest.main()

# SPDX-License-Identifier: GPL-3.0-or-later
"""The release order of update-manifest.py must be the app's (UpdateCheckTest.kt) and the PC library's.

python3 -I scripts/test_update_manifest.py
"""

import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("update_manifest", os.path.join(HERE, "update-manifest.py"))
um = importlib.util.module_from_spec(spec)
sys.dont_write_bytecode = True
spec.loader.exec_module(um)


class VersionOrderTests(unittest.TestCase):
    def assertOrdered(self, versions):
        self.assertEqual(versions, sorted(reversed(versions), key=um.version_key))

    def test_the_workflow_scheme(self):
        self.assertOrdered(["0.9.9", "0.9.9.1", "0.9.9.2", "0.9.10-beta-1", "0.9.10-beta-2", "0.9.10-beta-10", "0.9.10",
                            "0.9.10.1", "0.10.0"])

    def test_dots_and_hyphens_separate_the_same_way(self):
        self.assertEqual(um.version_key("0.9.10-beta-1"), um.version_key("0.9.10-beta.1"))

    def test_a_number_sorts_before_a_word_like_in_the_app(self):
        self.assertOrdered(["1.0.0-1", "1.0.0-beta", "1.0.0"])

    def test_channels(self):
        self.assertEqual(["prod", "beta", "test", "dev"],
                         [um.channel_of(v) for v in ("0.9.10", "0.9.10-beta-1", "0.9.10-test-1", "0.9.10-dev.2")])


if __name__ == "__main__":
    unittest.main()

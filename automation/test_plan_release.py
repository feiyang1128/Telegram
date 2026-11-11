import unittest

from plan_release import select_release


def release(code, fingerprint="old", draft=False, prerelease=False, apk=True):
    return {"tag_name": f"fork-{code}", "body": f"Build fingerprint: {fingerprint}",
            "draft": draft, "prerelease": prerelease,
            "assets": [{"name": f"fork-{code}-arm64.apk"}] if apk else []}


class ReleasePlanningTests(unittest.TestCase):
    def test_initial_and_legacy_versions(self):
        self.assertEqual(select_release(7291, 4, "new", []), (7291049, False))
        self.assertEqual(select_release(7291, 4, "new", [release(72919)]), (7291049, False))

    def test_changed_source_allocates_revision_without_manual_bump(self):
        self.assertEqual(select_release(7291, 4, "new", [release(7291049)]), (7291059, False))

    def test_identical_build_skips(self):
        self.assertEqual(select_release(7291, 4, "same", [release(7291059, "same")]), (7291059, True))

    def test_revert_still_gets_higher_version(self):
        self.assertEqual(select_release(7291, 4, "same", [release(7291049, "same"), release(7291059)]),
                         (7291069, False))

    def test_incomplete_or_unpublished_release_does_not_skip(self):
        for options in ({"draft": True}, {"prerelease": True}, {"apk": False}):
            with self.subTest(options=options):
                self.assertEqual(select_release(7291, 4, "same", [release(7291049, "same", **options)]),
                                 (7291059, False))

    def test_upstream_upgrade_and_exhaustion(self):
        self.assertEqual(select_release(7292, 4, "new", [release(7291999)]), (7292049, False))
        with self.assertRaises(ValueError):
            select_release(7291, 4, "new", [release(7291999)])


if __name__ == "__main__":
    unittest.main()

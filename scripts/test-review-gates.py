#!/usr/bin/env python3
"""Regression checks for generated project and completion inventories."""
import importlib.util
import tempfile
import unittest
from pathlib import Path


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


inventory = module("gradle-projects")
index = module("generate-completion-index")
claims = module("adr-status-claims")


class ReviewGatesTest(unittest.TestCase):
    def test_project_inventory_tracks_new_modules_and_excludes_comments(self):
        self.assertEqual([":app", ":core:new"], inventory.projects('include(":app", /* old */ ":core:new",)'))

    def test_dynamic_empty_and_duplicate_projects_fail(self):
        for text in ['include(projects)', 'include()', 'include(":app", ":app")', 'rootProject.name = "none"']:
            with self.subTest(text=text), self.assertRaises(ValueError):
                inventory.projects(text)

    def test_adr_claim_rejects_stale_status_but_preserves_explicit_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "0001.md").write_text("Status: superseded\n")
            stale = "accepted [ADR-0001](0001.md)"
            self.assertEqual(1, len(list(claims.mismatches(stale, root))))
            self.assertEqual([], list(claims.mismatches("历史状态：" + stale, root)))
            self.assertEqual([], list(claims.mismatches("superseded [ADR-0001](0001.md)", root)))

    def test_index_tracks_noncontiguous_records_and_historical_heading_levels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = root / "docs/completion-records"
            records.mkdir(parents=True)
            (records / "HXA-010.md").write_text("## Legacy title\n")
            (records / "HXA-012.md").write_text("# New | title\n")
            generated = index.render(root)
            self.assertIn("[Legacy title](HXA-010.md)", generated)
            self.assertIn("[New ／ title](HXA-012.md)", generated)
            self.assertNotIn("HXA-011", generated)
            (records / "HXA-012.md").unlink()
            self.assertNotEqual(generated, index.render(root))


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Regression checks for generated project and completion inventories."""
import importlib.util
import subprocess
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
    def test_adr_gate_checks_topic_ids_links_and_current_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("verify-adr.sh", "adr-status-claims.py"):
                (scripts / name).write_text(Path(__file__).with_name(name).read_text())
            for name in ("AGENTS.md", "README.md", "docs/development/status.md",
                         "docs/development/roadmap.md", "docs/development/implementation-guide.md"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("")
            topic = root / "docs/adr/goal"
            topic.mkdir(parents=True)
            new = topic / "001-current.md"
            sections = "\n".join("## " + title + "\nEvidence.\n" for title in (
                "Context", "Decision", "Alternatives considered", "Consequences",
                "Verification", "Reconsider when", "References"))
            def record(identifier, status="accepted"):
                return (f"# ADR-{identifier}: Fixture\n\nStatus: {status}\nDate: 2026-09-16\n"
                        f"HXA: HXA-001\nDeciders: Project owner\n\n{sections}")
            new.write_text(record("GOAL-001"))
            topic_index = topic / "README.md"
            topic_index.write_text("accepted [ADR-GOAL-001](001-current.md)\n")
            provider = root / "docs/adr/provider"
            provider.mkdir()
            (provider / "001-current.md").write_text(record("PROVIDER-001"))
            (provider / "README.md").write_text("accepted [ADR-PROVIDER-001](001-current.md)\n")
            command = ["bash", str(scripts / "verify-adr.sh")]
            def run():
                return subprocess.run(command, capture_output=True, text=True, timeout=15)
            valid = run()
            self.assertEqual(0, valid.returncode, valid.stderr)
            self.assertIn("2 current decision records", valid.stdout)
            new.write_text(new.read_text() + "\n[Broken](missing.md)\n")
            self.assertIn("unresolved relative link", run().stderr)
            new.write_text(record("GOAL-001"))
            duplicate = topic / "001-duplicate.md"
            duplicate.write_text(record("GOAL-001"))
            self.assertIn("ADR identifiers must be unique", run().stderr)
            duplicate.unlink()
            new.write_text(record("PROVIDER-001"))
            self.assertIn("topic/number and title differ", run().stderr)
            new.write_text(record("GOAL-001"))
            topic_index.write_text("proposed [ADR-GOAL-001](001-current.md)\n")
            self.assertIn("topic README must link", run().stderr)
            topic_index.write_text("accepted [ADR-GOAL-001](001-current.md)\n")
            new.write_text(record("GOAL-001", "superseded"))
            self.assertIn("invalid current decision Status", run().stderr)
            new.write_text(record("GOAL-001") + "\nSupersedes: none\n")
            self.assertIn("obsolete supersession metadata", run().stderr)

    def test_i18n_checks_the_current_worktree_even_under_excluded_parent_names(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "build/.claude/worktrees/fixture"
            scripts = root / "scripts"
            scripts.mkdir(parents=True)
            checker = Path(__file__).with_name("check-i18n.sh").read_text()
            (scripts / "check-i18n.sh").write_text(checker)
            chat = root / "app/src/main/kotlin/com/helix/app/chat/ChatService.kt"
            chat.parent.mkdir(parents=True)
            chat.write_text("// no diagnostic sink in this fixture\n")
            resources = root / "app/src/main/res"
            for locale in ("values", "values-en", "values-zh-rCN"):
                (resources / locale).mkdir(parents=True)
                content = '<resources><string name="fixture">Hello</string></resources>'
                (resources / locale / "strings.xml").write_text(
                    "<resources/>" if locale == "values-en" else content
                )
            # Exclusions still apply to nested worktrees and generated files INSIDE the root.
            for excluded in ("build", ".claude/worktrees/other"):
                nested = root / excluded / "app/src/main/res/values/strings.xml"
                nested.parent.mkdir(parents=True)
                nested.write_text('<resources><string name="nested">Skip</string></resources>')
            command = ["bash", str(scripts / "check-i18n.sh")]
            missing = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(0, missing.returncode)
            self.assertIn("keys missing from app/src/main/res/values-en", missing.stderr)
            self.assertNotIn("nested", missing.stderr)
            (resources / "values-en/strings.xml").write_text(content)
            complete = subprocess.run(command, capture_output=True, text=True, timeout=15)
            self.assertEqual(0, complete.returncode, complete.stderr)
            self.assertIn("1 resource keys in parity", complete.stdout)

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
            stale = "accepted [ADR-GOAL-001](0001.md)"
            self.assertEqual(1, len(list(claims.mismatches(stale, root))))
            self.assertEqual([], list(claims.mismatches("历史状态：" + stale, root)))
            self.assertEqual([], list(claims.mismatches("superseded [ADR-GOAL-001](0001.md)", root)))

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

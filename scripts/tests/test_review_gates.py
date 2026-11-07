#!/usr/bin/env python3
"""Regression checks for generated project and completion inventories."""
import importlib.util
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


inventory = module("gradle-projects")
index = module("generate-completion-index")
claims = module("adr-status-claims")


class ReviewGatesTest(unittest.TestCase):
    def test_docs_gate_checks_worktree_markdown_including_new_and_staged_files(self):
        source = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            # Reuse maintained documentation read-only; only the new entry links are fixtures.
            for path in source.iterdir():
                if path.name not in {".git", ".gitignore", ".gitattributes", ".agents", "scripts", "reviews", "evals", "build"}:
                    (root / path.name).symlink_to(path, target_is_directory=path.is_dir())
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("check-docs.sh", "generate-completion-index.py"):
                shutil.copy2(source / "scripts" / name, scripts / name)
            skill = root / ".agents/skills/fixture/SKILL.md"
            skill.parent.mkdir(parents=True)
            entry = scripts / "README.md"
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            def run():
                return subprocess.run(["bash", str(scripts / "check-docs.sh")],
                                      capture_output=True, text=True, timeout=60)
            entries = [skill, entry, root / "reviews/new review.md",
                       root / "evals/guide.md", root / "module/README.md",
                       scripts / "nested/guide.md"]
            for path in entries:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("[Project](" + os.path.relpath(root / "README.md", path.parent) + ")\n")
            subprocess.run(["git", "-C", str(root), "add", "--",
                            *(str(path.relative_to(root)) for path in entries)], check=True)
            # New maintained documents are checked before staging, while ignored
            # build outputs and local historical archives remain outside the gate.
            untracked = root / "new docs/new guide.md"
            untracked.parent.mkdir()
            untracked.write_text("[Project](../README.md)\n")
            fixture_links = ["/" + path.name for path in root.iterdir() if path.is_symlink()]
            (root / ".gitignore").write_text("\n".join([*fixture_links, "local/", "build/"]) + "\n")
            for relative in ("local/ignored.md", "build/ignored.md"):
                path = root / relative
                path.parent.mkdir()
                path.write_text("[Broken](missing.md)\n")
            valid = run()
            self.assertEqual(0, valid.returncode, valid.stderr)
            self.assertIn("7 Markdown files", valid.stdout)
            untracked.write_text("[Broken](missing.md)\n")
            self.assertIn("new docs/new guide.md: has an unresolved relative link", run().stderr)
            untracked.write_text("[Project](../README.md)\n")
            subprocess.run(["git", "-C", str(root), "add", "--", "new docs/new guide.md"], check=True)
            self.assertIn("7 Markdown files", run().stdout)
            entry.write_text("[Local](../local/ignored.md)\n")
            ignored_link = run()
            self.assertNotEqual(0, ignored_link.returncode)
            self.assertIn("links to an ignored local artifact", ignored_link.stderr)
            # These valid contents were staged, but a later invalid worktree edit
            # must still fail. No git-show/index overlay may replace the worktree.
            for path in [*entries, untracked]:
                path.write_text("[Broken](missing.md)\n")
            invalid = run()
            self.assertNotEqual(0, invalid.returncode)
            for path in [*entries, untracked]:
                self.assertIn(str(path.relative_to(root)) + ": has an unresolved relative link", invalid.stderr)
            self.assertNotIn("local/ignored.md:", invalid.stderr)
            self.assertNotIn("build/ignored.md:", invalid.stderr)
            self.assertNotIn("Traceback", invalid.stderr)
            # A deleted tracked file is not a reader crash; other broken links still fail.
            entries[-1].unlink()
            self.assertNotIn("Traceback", run().stderr)

    def test_adr_gate_checks_topic_ids_links_and_current_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            scripts = root / "scripts"
            scripts.mkdir()
            for name in ("verify-adr.sh", "adr-status-claims.py"):
                (scripts / name).write_text((Path(__file__).resolve().parents[1] / name).read_text())
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
            checker = (Path(__file__).resolve().parents[1] / "check-i18n.sh").read_text()
            (scripts / "check-i18n.sh").write_text(checker)
            for display in (
                "feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation/ChatToolPresentation.kt",
                "feature/conversation/src/main/kotlin/com/helix/feature/conversation/presentation/ChatToolTimeline.kt",
                "app/src/main/kotlin/com/helix/app/infrastructure/conversation/ConversationToolDisplay.kt",
            ):
                path = root / display
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// Synthetic empty tool display fixture\n")
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

    def test_detekt_inputs_are_configured_module_sources_not_repository_archives(self):
        source = Path(__file__).resolve().parents[2]
        build = (source / "build.gradle.kts").read_text()
        # Kotlin formatting may wrap property chains and lambda bodies. Match the
        # configuration tokens rather than a particular Spotless line layout.
        compact = re.sub(r"\s+", "", re.sub(r"(?m)^\s*//.*$", "", build))
        detekt = compact.split('tasks.register<JavaExec>("detekt"){', 1)[1].split("valandroidLibraries", 1)[0]
        # Pin the production scope, not an exclusion list that grows with archives.
        self.assertIn('valsourceRoots=subprojects.map{it.layout.projectDirectory.dir("src").asFile}', detekt)
        self.assertIn('sourceRoots.map{sourceRoot->', detekt)
        self.assertIn('fileTree(sourceRoot)', detekt)
        self.assertIn('include("**/*.kt")', detekt)
        self.assertIn('inputs.files(kotlinSources)', detekt)
        self.assertIn('sourceRoots.filter{it.isDirectory}', detekt)
        self.assertNotIn('fileTree(projectDir)', detekt)
        self.assertNotIn('generateSequence', detekt)
        modules = inventory.projects((source / "settings.gradle.kts").read_text())
        roots = [module[1:].replace(":", "/") + "/src/" for module in modules]
        def included(path):
            return any(path.startswith(root) for root in roots) and path.endswith(".kt")
        for path in (
            "app/src/main/kotlin/Product.kt", "app/src/consumer/kotlin/Consumer.kt",
            "app/src/developer/kotlin/Developer.kt", "core/model/src/test/kotlin/ModelTest.kt",
            "app/src/androidTestDeveloper/java/DeviceTest.kt",
            "spikes/a2a-sdk/src/main/kotlin/Spike.kt",
            "tests/release-smoke/src/androidTest/kotlin/Smoke.kt",
        ):
            with self.subTest(path=path):
                self.assertTrue(included(path))
        for path in (
            "scripts/local/debug/archive/Old.kt", "scripts/debug/2026-09-01/local/Old.kt",
            "scripts/experiments/fixture/src/main/kotlin/Standalone.kt",
            "app/build/generated/Generated.kt", "unknown/src/main/kotlin/Unmanaged.kt",
        ):
            with self.subTest(path=path):
                self.assertFalse(included(path))

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

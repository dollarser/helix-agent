"""Archive useful evidence, then retire only the owner's explicitly approved old worktrees."""
from pathlib import Path
import json
import os
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
names = ["Helix-connectors", "Helix-ev-baseline-repair", "Helix-m7", "Helix-m8",
         "Helix-m9", "Helix-m10", "Helix-m11", "Helix-m11-main",
         "Helix-phone-chat-polish", "Helix-verification-gaps", "helix-ev04-wt"]
archive = root / "build/worktree-retirement/2026-09-13"
refs = root / "scripts/debug/2026-09-13/ev04-frozen-reference"
unique = ["app/src/androidTest/kotlin/com/helix/app/MainAppCombinedSoakDeviceTest.kt",
          "app/src/androidTestDeveloper/kotlin/com/helix/app/ProotAnchorFixture.kt",
          "runtime/proot-app/src/androidTest/kotlin/com/helix/runtime/proot/app/ProotRootfsSoakInstallDeviceTest.kt"]
duplicates = ["app/src/androidTest/kotlin/com/helix/app/provider/InAppMcpServer.kt",
              "app/src/androidTest/kotlin/com/helix/app/provider/ScriptedTaskModelServer.kt"]


def git(*args, cwd=root):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True).strip()


assert git("branch", "--show-current") == "main"
if "--remove" not in sys.argv:
    archive.mkdir(parents=True, exist_ok=False)
    manifest = []
    for name in names:
        path = root.parent / name
        assert path.is_dir() and not path.is_symlink()
        head = git("rev-parse", "HEAD", cwd=path)
        assert git("rev-list", "--count", "main.." + head) == "0"
        branch = git("branch", "--show-current", cwd=path)
        assert not git("diff", "--name-only", "HEAD", cwd=path)
        untracked = git("ls-files", "--others", "--exclude-standard", cwd=path).splitlines()
        expected = unique + duplicates if name == "helix-ev04-wt" else (
            ["HXA-083-fix-handoff.md"] if name == "Helix-m8" else [])
        assert set(untracked) == set(expected), (name, untracked)
        ignored = git("ls-files", "--others", "--ignored", "--exclude-standard", cwd=path).splitlines()
        retained = []
        for relative in untracked + ignored:
            parts = Path(relative).parts
            # Preserve root-level evidence, module test reports/results, and local non-cache files.
            if relative not in untracked:
                if any(p in {".gradle", ".kotlin", ".codegraph", "node_modules", ".cxx"} for p in parts):
                    continue
                if "build" in parts and parts[0] != "build" and not any(
                    p in {"reports", "test-results", "outputs"} for p in parts
                ):
                    continue
            source = path / relative
            if not source.is_file() or source.is_symlink():
                continue
            destination = archive / name / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            # Hard links retain bytes after directory removal without duplicating large evidence.
            try:
                os.link(source, destination)
            except OSError:
                shutil.copy2(source, destination)
            assert source.stat().st_size == destination.stat().st_size
            retained.append(relative)
        if name == "helix-ev04-wt":
            for relative in duplicates:
                assert (path / relative).read_bytes() == (root / relative).read_bytes()
            refs.mkdir(parents=True, exist_ok=True)
            for relative in unique:
                shutil.copy2(path / relative, refs / (Path(relative).name + ".txt"))
        manifest.append({"name": name, "head": head, "branch": branch,
                         "untracked": untracked, "retained": retained})
        print(name, "archived", len(retained), flush=True)
    (archive / "manifest.json").write_text(json.dumps(manifest, indent=2))
else:
    manifest = json.loads((archive / "manifest.json").read_text())
    for entry in manifest:
        path = root.parent / entry["name"]
        assert git("rev-parse", "HEAD", cwd=path) == entry["head"]
        assert git("rev-list", "--count", "main.." + entry["head"]) == "0"
        assert not git("diff", "--name-only", "HEAD", cwd=path)
        assert set(git("ls-files", "--others", "--exclude-standard", cwd=path).splitlines()) == set(entry["untracked"])
        for relative in entry["untracked"]:
            assert (path / relative).read_bytes() == (archive / entry["name"] / relative).read_bytes()
        subprocess.run(["git", "worktree", "remove", "--force", str(path)], cwd=root, check=True)
        if entry["branch"]:
            subprocess.run(["git", "branch", "-d", entry["branch"]], cwd=root, check=True)
        print("retired", entry["name"], flush=True)

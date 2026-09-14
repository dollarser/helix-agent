"""Read-only retention inventory; never remove a worktree or branch."""
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]


def git(*args, cwd=root):
    return subprocess.check_output(["git", *args], cwd=cwd, text=True)


rows = []
for block in git("worktree", "list", "--porcelain").strip().split("\n\n"):
    facts = dict(line.split(" ", 1) if " " in line else (line, True) for line in block.splitlines())
    path = Path(facts["worktree"])
    if not path.exists():
        rows.append({"path": str(path), "missing": True})
        continue
    status = git("status", "--porcelain", "--untracked-files=all", cwd=path).splitlines()
    ignored = git("ls-files", "--others", "--ignored", "--exclude-standard", cwd=path).splitlines()
    ahead = git("rev-list", "--count", "main.." + facts["HEAD"]).strip()
    rows.append({"path": str(path), "branch": facts.get("branch", "DETACHED"),
                 "head": facts["HEAD"], "commits_not_in_main": int(ahead),
                 "dirty_count": len(status), "dirty_paths": status[:30],
                 "ignored_count": len(ignored),
                 "ignored_evidence": [p for p in ignored if p.endswith((".md", ".json", ".log"))][:12]})
out = root / "build/debug/2026-09-13/worktree-retention.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(rows, indent=2))
print(json.dumps(rows, indent=2))

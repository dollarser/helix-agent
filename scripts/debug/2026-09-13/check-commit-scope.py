"""Freeze and inspect main's explicit changed paths without printing sensitive matches."""
from pathlib import Path
import json
import re
import subprocess

root = Path(__file__).resolve().parents[3]
assert subprocess.check_output(["git", "branch", "--show-current"], cwd=root, text=True).strip() == "main"
paths = sorted(set(subprocess.check_output(
    ["git", "ls-files", "-m", "-d", "--others", "--exclude-standard", "-z"], cwd=root
).decode().strip("\0").split("\0")))
paths = sorted(set(paths) | set(subprocess.check_output(
    ["git", "diff", "--cached", "--name-only", "-z"], cwd=root
).decode().strip("\0").split("\0")))
paths = [name for name in paths if name]
scanner = (root / "scripts/check-secrets.sh").read_text()
pattern = re.compile(re.search(r"secret_pattern='([^']+)'", scanner)[1])
assert not pattern.search("2026-09-10-file-task-context-and-approval.md")
assert pattern.search('token="' + "sk-" + "x" * 24 + '"')
issues = []
for name in paths:
    path = root / name
    if not path.exists():
        continue
    data = path.read_bytes()
    if path.is_symlink() or len(data) > 2_000_000 or b"\0" in data:
        issues.append((name, "symlink, binary or oversized file"))
        continue
    text = data.decode()
    if name != "scripts/check-secrets.sh" and pattern.search(text):
        issues.append((name, "potential secret"))
    if re.search(r"/Users/[A-Za-z0-9_.-]+/", text):
        issues.append((name, "host-specific absolute path"))
out = root / "build/debug/2026-09-13"
out.mkdir(parents=True, exist_ok=True)
(out / "commit-paths.nul").write_bytes("\0".join(paths).encode() + b"\0")
print(json.dumps({"files": len(paths), "issues": issues}, indent=2))
raise SystemExit(bool(issues))

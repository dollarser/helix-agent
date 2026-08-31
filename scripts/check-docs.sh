#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HELIX_PROJECT_ROOT="$project_root"

python3 <<'PY'
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

root = Path(os.environ["HELIX_PROJECT_ROOT"]).resolve()
markdown_files = sorted([*root.glob("*.md"), *(root / "docs").rglob("*.md")])
link_pattern = re.compile(r"(?<!!)\[[^]]*]\(([^)]+)\)")
errors: list[str] = []


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(root)}: {message}")


for path in markdown_files:
    text = path.read_text(encoding="utf-8")
    for raw_target in link_pattern.findall(text):
        target = raw_target.strip().split(maxsplit=1)[0].strip("<>").split("#", 1)[0]
        if not target or re.match(r"^(?:https?|mailto):", target):
            continue
        if target.startswith("/"):
            fail(path, f"uses an absolute repository link: {raw_target}")
            continue
        if not (path.parent / target).resolve().exists():
            fail(path, f"has an unresolved relative link: {raw_target}")

roadmap = (root / "docs" / "04-roadmap-and-backlog.md").read_text(encoding="utf-8")
matrix = (root / "docs" / "verification-matrix.md").read_text(encoding="utf-8")
roadmap_ids = re.findall(r"^### (HXA-\d{3})\b", roadmap, re.MULTILINE)
matrix_ids = re.findall(r"^\| (HXA-\d{3}) \|", matrix, re.MULTILINE)

if len(roadmap_ids) != len(set(roadmap_ids)):
    errors.append("docs/04-roadmap-and-backlog.md: duplicate HXA task heading")
if len(matrix_ids) != len(set(matrix_ids)):
    errors.append("docs/verification-matrix.md: duplicate HXA row")
if set(roadmap_ids) != set(matrix_ids):
    missing = sorted(set(roadmap_ids) - set(matrix_ids))
    extra = sorted(set(matrix_ids) - set(roadmap_ids))
    errors.append(f"HXA matrix mismatch; missing={missing}, extra={extra}")

required_status_sections = (
    "Completed",
    "In progress",
    "Next task",
    "Blocked",
    "Current interfaces",
    "Known limitations",
)
status_path = root / "docs" / "implementation-status.md"
status = status_path.read_text(encoding="utf-8")
for section in required_status_sections:
    if len(re.findall(rf"^## {re.escape(section)}$", status, re.MULTILINE)) != 1:
        fail(status_path, f"requires exactly one '## {section}' section")

handoff_path = root / "docs" / "small-model-handoff.md"
handoff = handoff_path.read_text(encoding="utf-8")
for required_text in ("Goal: HELIX-M1", "HXA-010", "HXA-016", "verification matrix"):
    if required_text not in handoff:
        fail(handoff_path, f"missing handoff contract text: {required_text}")

if errors:
    print("Documentation verification failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print(
    "Documentation verification passed "
    f"({len(markdown_files)} Markdown files, {len(roadmap_ids)} HXA tasks)."
)
PY

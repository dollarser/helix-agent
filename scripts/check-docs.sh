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

# Completion-record contract: every "Mx / HXA-NNN 已完成" bullet in the Completed section
# must point at an existing record (M0: the single m0-completion-record.md; M1+: one file
# per HXA in completion-records/), and every M1+ record must contain a 决策记录 section
# (completion-records/README.md). This is what makes the status file auditable.
completed_match = re.search(r"^## Completed\n(.*?)(?=^## )", status, re.MULTILINE | re.DOTALL)
completed_section = completed_match.group(1) if completed_match else ""
for milestone, task_id in re.findall(r"^M(\d+) / (HXA-\d{3}) 已完成", completed_section, re.MULTILINE):
    if milestone == "0":
        record = root / "docs" / "m0-completion-record.md"
    else:
        record = root / "docs" / "completion-records" / f"{task_id}.md"
    if not record.is_file():
        fail(status_path, f"'M{milestone} / {task_id} 已完成' has no completion record: {record.relative_to(root)}")
        continue
    if milestone != "0" and "决策记录：" not in record.read_text(encoding="utf-8"):
        fail(record, "missing the '决策记录：' section required by completion-records/README.md")

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

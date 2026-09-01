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

# Completion-record contract: the compact milestone table in Completed declares inclusive
# HXA ranges (for example "| M3 | HXA-030～038：… |"). Every declared task must have
# evidence (M0: the combined m0-completion-record.md; M1+: one file per HXA), every M1+
# record must contain a 决策记录 section, and no M1+ record may silently disappear from
# the status summary. Keep accepting the legacy per-HXA bullet form while old branches migrate.
completed_match = re.search(r"^## Completed\n(.*?)(?=^## )", status, re.MULTILINE | re.DOTALL)
completed_section = completed_match.group(1) if completed_match else ""
completed_entries = re.findall(r"^\s*[-*]\s*M(\d+) / (HXA-\d{3}) 已完成", completed_section, re.MULTILINE)
for milestone, start, end in re.findall(
    r"^\|\s*M(\d+)\s*\|\s*HXA-(\d{3})～(?:HXA-)?(\d{3})\b",
    completed_section,
    re.MULTILINE,
):
    if int(start) > int(end):
        fail(status_path, f"M{milestone} has a reversed completed range: HXA-{start}～{end}")
        continue
    completed_entries.extend(
        (milestone, f"HXA-{task_number:03d}")
        for task_number in range(int(start), int(end) + 1)
    )

if not completed_entries:
    fail(status_path, "Completed has neither milestone ranges nor legacy per-HXA bullets (gate would be vacuous)")

listed_task_ids: set[str] = set()
for milestone, task_id in completed_entries:
    if task_id in listed_task_ids:
        fail(status_path, f"Completed lists {task_id} more than once")
        continue
    listed_task_ids.add(task_id)
    if milestone == "0":
        record = root / "docs" / "m0-completion-record.md"
    else:
        record = root / "docs" / "completion-records" / f"{task_id}.md"
    if not record.is_file():
        fail(status_path, f"'M{milestone} / {task_id} 已完成' has no completion record: {record.relative_to(root)}")
        continue
    if milestone != "0" and "决策记录：" not in record.read_text(encoding="utf-8"):
        fail(record, "missing the '决策记录：' section required by completion-records/README.md")

record_task_ids = {
    path.stem
    for path in (root / "docs" / "completion-records").glob("HXA-*.md")
}
listed_record_task_ids = {
    task_id
    for milestone, task_id in completed_entries
    if milestone != "0"
}
if listed_record_task_ids != record_task_ids:
    missing_from_status = sorted(record_task_ids - listed_record_task_ids)
    missing_records = sorted(listed_record_task_ids - record_task_ids)
    fail(
        status_path,
        "completion-record index mismatch; "
        f"missing_from_status={missing_from_status}, missing_records={missing_records}",
    )

guide_path = root / "docs" / "08-small-model-implementation-guide.md"
guide = guide_path.read_text(encoding="utf-8")
for required_text in (
    "implementation-status",
    "已有完成记录的 HXA 不得重复实现",
    "只有用户明确要求建立持久 Goal 时才创建",
    "verification matrix",
):
    if required_text not in guide:
        fail(guide_path, f"missing implementation-guide contract text: {required_text}")

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

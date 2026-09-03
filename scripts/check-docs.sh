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


required_doc_entries = (
    "README.md",
    "product/requirements.md",
    "product/competitive-landscape.md",
    "product/market-users-and-commercialization.md",
    "architecture/overview.md",
    "architecture/local-code-execution.md",
    "architecture/android-platform-capabilities.md",
    "architecture/provider-mcp-skills-modes.md",
    "architecture/mobile-tool-orchestration.md",
    "development/status.md",
    "development/roadmap.md",
    "development/environment.md",
    "development/implementation-guide.md",
    "development/verification-matrix.md",
    "security/testing-and-release.md",
    "references/open-source-projects.md",
    "history/documentation-review.md",
)
for relative_path in required_doc_entries:
    required_path = root / "docs" / relative_path
    if not required_path.is_file():
        fail(required_path, "required documentation entry is missing")

unexpected_top_level_docs = sorted(
    path.name for path in (root / "docs").glob("*.md") if path.name != "README.md"
)
if unexpected_top_level_docs:
    errors.append(
        "docs/: Markdown files must be assigned to a documented category; "
        f"unexpected={unexpected_top_level_docs}"
    )


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

roadmap_path = root / "docs" / "development" / "roadmap.md"
matrix_path = root / "docs" / "development" / "verification-matrix.md"
roadmap = roadmap_path.read_text(encoding="utf-8")
matrix = matrix_path.read_text(encoding="utf-8")
roadmap_ids = re.findall(r"^### (HXA-\d{3})\b", roadmap, re.MULTILINE)
matrix_ids = re.findall(r"^\| (HXA-\d{3}) \|", matrix, re.MULTILINE)

if len(roadmap_ids) != len(set(roadmap_ids)):
    errors.append("docs/development/roadmap.md: duplicate HXA task heading")
if len(matrix_ids) != len(set(matrix_ids)):
    errors.append("docs/development/verification-matrix.md: duplicate HXA row")
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
status_path = root / "docs" / "development" / "status.md"
status = status_path.read_text(encoding="utf-8")
for section in required_status_sections:
    if len(re.findall(rf"^## {re.escape(section)}$", status, re.MULTILINE)) != 1:
        fail(status_path, f"requires exactly one '## {section}' section")

# Completion-record contract: the compact milestone table in Completed declares inclusive
# HXA ranges (for example "| M3 | HXA-030～038：… |"). Every declared task must have
# evidence (M0: the combined completion-records/M0.md; M1+: one file per HXA), every M1+
# record must contain a 决策记录 section, and no M1+ record may silently disappear from
# the status summary. Keep accepting the legacy per-HXA bullet form while old branches migrate.
completed_match = re.search(r"^## Completed\n(.*?)(?=^## )", status, re.MULTILINE | re.DOTALL)
completed_section = completed_match.group(1) if completed_match else ""
# M(\d+[A-Za-z]?) accepts lettered sub-milestones (e.g. M5A/M5B) introduced by the roadmap's
# §9A/§9B, in addition to the plain numeric milestones.
completed_entries = re.findall(r"^\s*[-*]\s*M(\d+[A-Za-z]?) / (HXA-\d{3}) 已完成", completed_section, re.MULTILINE)
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
        record = root / "docs" / "completion-records" / "M0.md"
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

# Bug-fix records are durable defect decisions, not free-form review logs. Their filename,
# lifecycle metadata, HXA ownership, and section order are mechanical so a "fixed" record
# cannot enter the repository without a root cause, regression evidence, or residual-risk
# statement. README files define policy and are intentionally excluded from record parsing.
bug_fix_dir = root / "docs" / "bug-fixes"
postmortem_dir = root / "docs" / "postmortems"
for required_readme in (bug_fix_dir / "README.md", postmortem_dir / "README.md"):
    if not required_readme.is_file():
        fail(required_readme, "required documentation policy is missing")

bug_fix_sections = (
    "Problem",
    "Impact",
    "Root cause",
    "Fix and invariants",
    "Alternatives considered",
    "Regression verification",
    "Residual risk",
    "Related records",
)
bug_fix_name = re.compile(r"^(\d{4}-\d{2}-\d{2})-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
for path in sorted(bug_fix_dir.glob("*.md")):
    if path.name == "README.md":
        continue
    name_match = bug_fix_name.fullmatch(path.name)
    if not name_match:
        fail(path, "filename must be YYYY-MM-DD-short-kebab-title.md")
        continue
    text = path.read_text(encoding="utf-8")
    if not re.search(r"^# Bug Fix: \S", text, re.MULTILINE):
        fail(path, "requires a '# Bug Fix: <title>' heading")
    status_matches = re.findall(r"^Status: (\S+)$", text, re.MULTILINE)
    if status_matches not in (["fixed"], ["superseded"]):
        fail(path, "requires exactly one Status: fixed|superseded")
    if f"Date: {name_match.group(1)}" not in text:
        fail(path, "Date must exactly match the filename date")
    related_line = re.search(r"^Related HXA: (.+)$", text, re.MULTILINE)
    related_ids = re.findall(r"HXA-\d{3}", related_line.group(1)) if related_line else []
    if not related_ids:
        fail(path, "requires at least one Related HXA")
    for task_id in related_ids:
        if task_id not in roadmap_ids:
            fail(path, f"references unknown HXA task: {task_id}")
    positions: list[int] = []
    for section in bug_fix_sections:
        matches = list(re.finditer(rf"^## {re.escape(section)}$", text, re.MULTILINE))
        if len(matches) != 1:
            fail(path, f"requires exactly one '## {section}' section")
        else:
            positions.append(matches[0].start())
    if len(positions) == len(bug_fix_sections) and positions != sorted(positions):
        fail(path, "required sections are out of order")

postmortem_sections = (
    "Executive summary",
    "Impact",
    "Timeline",
    "Root cause",
    "Why existing safeguards missed it",
    "Guardrails added",
    "Lessons",
    "Related records",
)
postmortem_numbers: set[str] = set()
for path in sorted(postmortem_dir.glob("*.md")):
    if path.name == "README.md":
        continue
    name_match = re.fullmatch(r"(\d{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md", path.name)
    if not name_match:
        fail(path, "filename must be NNNN-short-kebab-title.md")
        continue
    number = name_match.group(1)
    if number in postmortem_numbers:
        fail(path, f"duplicates postmortem number {number}")
    postmortem_numbers.add(number)
    text = path.read_text(encoding="utf-8")
    if not re.search(rf"^# Postmortem {number}: \S", text, re.MULTILINE):
        fail(path, f"title must start with '# Postmortem {number}:'")
    status_matches = re.findall(r"^Status: (\S+)$", text, re.MULTILINE)
    if status_matches not in (["resolved"], ["monitoring"]):
        fail(path, "requires exactly one Status: resolved|monitoring")
    positions = []
    for section in postmortem_sections:
        matches = list(re.finditer(rf"^## {re.escape(section)}$", text, re.MULTILINE))
        if len(matches) != 1:
            fail(path, f"requires exactly one '## {section}' section")
        else:
            positions.append(matches[0].start())
    if len(positions) == len(postmortem_sections) and positions != sorted(positions):
        fail(path, "required sections are out of order")

guide_path = root / "docs" / "development" / "implementation-guide.md"
guide = guide_path.read_text(encoding="utf-8")
for required_text in (
    "docs/development/status.md",
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

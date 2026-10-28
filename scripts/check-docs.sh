#!/usr/bin/env bash
set -euo pipefail

readonly project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export HELIX_PROJECT_ROOT="$project_root"

python3 <<'PY'
from __future__ import annotations

import os
import re
import sys
import subprocess
from pathlib import Path

root = Path(os.environ["HELIX_PROJECT_ROOT"]).resolve()
local_docs = root / "docs" / "local"
markdown_files = sorted(
    path for path in [*root.glob("*.md"), *(root / "docs").rglob("*.md"),
                      *(root / ".agents" / "skills").rglob("*.md"),
                      *root.glob("scripts/README.md")]
    if local_docs not in path.parents
)
link_pattern = re.compile(r"(?<!!)\[[^]]*]\(([^)]+)\)")
errors: list[str] = []
index_check = subprocess.run([sys.executable, str(root / "scripts/generate-completion-index.py"), "--check"], capture_output=True, text=True)
if index_check.returncode:
    errors.append(index_check.stderr.strip())


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
    "architecture/providers.md",
    "architecture/extensions.md",
    "architecture/agent-modes.md",
    "architecture/terminal.md",
    "architecture/mobile-tool-orchestration.md",
    "development/status.md",
    "development/roadmap.md",
    "development/environment.md",
    "development/implementation-guide.md",
    "development/verification-matrix.md",
    "security/testing-and-release.md",
    "references/open-source-projects.md",
    "evidence/development/documentation-review-history-2026-09-02.md",
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
        resolved = (path.parent / target).resolve()
        if resolved.is_relative_to(root):
            ignored = subprocess.run(["git", "check-ignore", "-q", "--", str(resolved)], cwd=root)
            if ignored.returncode == 0:
                fail(path, f"links to an ignored local artifact; use a plain code path: {raw_target}")
            elif ignored.returncode != 1:
                fail(path, f"could not verify git ignore state: {raw_target}")
        if not resolved.exists():
            fail(path, f"has an unresolved relative link: {raw_target}")

roadmap_path = root / "docs" / "development" / "roadmap.md"
matrix_path = root / "docs" / "development" / "verification-matrix.md"
roadmap = roadmap_path.read_text(encoding="utf-8")
matrix = matrix_path.read_text(encoding="utf-8")
roadmap_ids = re.findall(r"^\| (HXA-\d{3}) \|", roadmap, re.MULTILINE)
if not roadmap_ids or len(roadmap_ids) != len(set(roadmap_ids)):
    errors.append("docs/development/roadmap.md: missing or duplicate HXA task rows")
task_dir = root / "docs/development/tasks"
completed_ids = {p.stem for p in (root / "docs/completion-records").glob("HXA-*.md")}
completed_ids.update({"HXA-001", "HXA-002", "HXA-003"})
active_ids = {p.stem for p in task_dir.glob("HXA-*.md")}
if active_ids != set(roadmap_ids) - completed_ids:
    errors.append(f"Task inventory mismatch: expected={sorted(set(roadmap_ids)-completed_ids)}, actual={sorted(active_ids)}")
for task_path in task_dir.glob("HXA-*.md"):
    task_text = task_path.read_text(encoding="utf-8")
    for heading in ("范围与验收要求", "验证入口", "交付"):
        if len(re.findall(rf"^## {heading}$", task_text, re.MULTILINE)) != 1:
            fail(task_path, f"requires exactly one {heading} section")
    if not task_text.startswith(f"# {task_path.stem}："):
        fail(task_path, "task title does not match filename")

required_status_sections = (
    "当前能力与唯一说明入口",
    "最新验收及未通过项",
    "仍开放与未启动",
    "查阅方式",
)
status_path = root / "docs" / "development" / "status.md"
status = status_path.read_text(encoding="utf-8")
for section in required_status_sections:
    if len(re.findall(rf"^## {re.escape(section)}$", status, re.MULTILINE)) != 1:
        fail(status_path, f"requires exactly one '## {section}' section")

# The generated completion index is the inventory; status links it instead of duplicating
# every completed task. Keep evidence/decision requirements for every record.
if "[完成记录索引](../completion-records/index.md)" not in status:
    fail(status_path, "must link the authoritative completion-record index")
for record in (root / "docs/completion-records").glob("HXA-*.md"):
    if "决策记录：" not in record.read_text(encoding="utf-8"):
        fail(record, "missing the '决策记录：' section required by completion-records/README.md")

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
    "(status.md)",
    "已完成 HXA 不重复实施",
    "只有用户明确要求建立持久 Goal 时才创建",
    "(verification-matrix.md)",
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

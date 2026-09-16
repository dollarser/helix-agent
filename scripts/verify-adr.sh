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
adr_dir = root / "docs/adr"
filename_pattern = re.compile(r"^(\d{3})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$")
title_pattern = re.compile(r"^# (ADR-([A-Z0-9]+)-(\d{3})): .+$", re.MULTILINE)
link_pattern = re.compile(r"(?<!!)\[[^]]*]\(([^)]+)\)")
fields = ("Status", "Date", "HXA", "Deciders")
sections = ("Context", "Decision", "Alternatives considered", "Consequences",
            "Verification", "Reconsider when", "References")
errors: list[str] = []
records: dict[str, tuple[Path, str]] = {}


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(root)}: {message}")


for path in sorted(adr_dir.rglob("*.md")):
    text = path.read_text(encoding="utf-8")
    if re.search(r"/(?:Users|home)/[^/\s]+/|[A-Za-z]:\\(?:Users|Documents)\\", text):
        fail(path, "contains a machine-local absolute path")
    for raw_target in link_pattern.findall(text):
        target = raw_target.strip().split(maxsplit=1)[0].strip("<>").split("#", 1)[0]
        if not target or re.match(r"^(?:https?|mailto):", target):
            continue
        if target.startswith("/"):
            fail(path, f"uses an absolute Markdown link: {raw_target}")
        elif not (path.parent / target).resolve().exists():
            fail(path, f"has an unresolved relative link: {raw_target}")
    if path.name == "README.md":
        continue
    filename = filename_pattern.fullmatch(path.name)
    title = title_pattern.search(text)
    if len(path.relative_to(adr_dir).parts) != 2:
        fail(path, "decision must be directly under one topic, not a history directory")
    if not filename or not title:
        fail(path, "requires NNN-topic.md filename and '# ADR-TOPIC-NNN: title'")
        continue
    identifier, topic, number = title.groups()
    if topic.lower() != path.parent.name or number != filename[1]:
        fail(path, "topic/number and title differ")
    if identifier in records:
        fail(path, "ADR identifiers must be unique within each topic")
    metadata = {}
    for field in fields:
        values = re.findall(rf"^{field}:\s*(\S.*)$", text, re.MULTILINE)
        if len(values) != 1:
            fail(path, f"requires exactly one '{field}' field")
        else:
            metadata[field] = values[0].strip()
    status = metadata.get("Status", "")
    if status not in {"accepted", "proposed"}:
        fail(path, f"invalid current decision Status '{status}'")
    if status == "accepted" and metadata.get("Deciders") == "pending":
        fail(path, "accepted decision requires named Deciders")
    if re.search(r"^Supersede(?:s|d by):", text, re.MULTILINE):
        fail(path, "obsolete supersession metadata; consolidate the current decision")
    if re.search(r"\bADR-\d{4}\b", text):
        fail(path, "obsolete global ADR identifier")
    for section in sections:
        if len(re.findall(rf"^## {re.escape(section)}\s*$", text, re.MULTILINE)) != 1:
            fail(path, f"requires exactly one '## {section}' section")
    records[identifier] = (path, status)

if not records:
    errors.append("docs/adr: no current decisions found")
# Every decision is discoverable through its topic index, with its actual status.
for identifier, (path, status) in records.items():
    index = path.parent / "README.md"
    expected = f"{status} [{identifier}]({path.name})"
    if not index.is_file() or expected not in index.read_text():
        fail(path, "topic README must link the decision with its current status")
if errors:
    print("ADR verification failed:\n" + "\n".join(f"- {e}" for e in errors), file=sys.stderr)
    raise SystemExit(1)
print(f"ADR verification passed ({len(records)} current decision records).")
PY

python3 "$project_root/scripts/adr-status-claims.py"

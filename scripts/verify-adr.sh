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
adr_dir = root / "docs" / "adr"
adr_pattern = re.compile(r"^(\d{4})-([a-z0-9]+(?:-[a-z0-9]+)*)\.md$")
title_pattern = re.compile(r"^# ADR-(\d{4}):\s+\S.*$", re.MULTILINE)
link_pattern = re.compile(r"(?<!!)\[[^]]*]\(([^)]+)\)")
field_names = (
    "Status",
    "Date",
    "HXA",
    "Deciders",
    "Supersedes",
    "Superseded by",
)
required_sections = (
    "Context",
    "Decision",
    "Alternatives considered",
    "Consequences",
    "Verification",
    "Reconsider when",
    "References",
)
valid_statuses = {"proposed", "accepted", "rejected", "superseded"}
errors: list[str] = []
records: dict[Path, dict[str, str]] = {}


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(root)}: {message}")


def link_target(value: str) -> str | None:
    match = link_pattern.search(value)
    if match:
        return match.group(1).split("#", 1)[0]
    if value.strip().lower() == "none":
        return None
    return value.strip().split("#", 1)[0]


for path in sorted(adr_dir.glob("*.md")):
    text = path.read_text(encoding="utf-8")

    if re.search(r"/(?:Users|home)/[^/\s]+/|[A-Za-z]:\\(?:Users|Documents)\\", text):
        fail(path, "contains a machine-local absolute path")

    for raw_target in link_pattern.findall(text):
        target = raw_target.strip().split(maxsplit=1)[0].strip("<>").split("#", 1)[0]
        if not target or re.match(r"^(?:https?|mailto):", target):
            continue
        if target.startswith("/"):
            fail(path, f"uses an absolute Markdown link: {raw_target}")
            continue
        if not (path.parent / target).resolve().exists():
            fail(path, f"has an unresolved relative link: {raw_target}")

    if path.name == "README.md":
        continue

    filename_match = adr_pattern.match(path.name)
    if not filename_match:
        fail(path, "filename must match NNNN-short-kebab-topic.md")
        continue

    number = filename_match.group(1)
    title_match = title_pattern.search(text)
    if not title_match:
        fail(path, "missing '# ADR-NNNN: title'")
    elif title_match.group(1) != number:
        fail(path, "filename and title ADR numbers differ")

    fields: dict[str, str] = {}
    for field in field_names:
        matches = re.findall(rf"^{re.escape(field)}:\s*(\S.*)$", text, re.MULTILINE)
        if len(matches) != 1:
            fail(path, f"requires exactly one '{field}' field")
        else:
            fields[field] = matches[0].strip()

    status = fields.get("Status", "")
    if status not in valid_statuses:
        fail(path, f"invalid Status '{status}'")
    if status == "implemented":
        fail(path, "Status 'implemented' is forbidden")
    if status in {"accepted", "rejected", "superseded"} and fields.get("Deciders") == "pending":
        fail(path, f"Status '{status}' requires named Deciders")

    for section in required_sections:
        if len(re.findall(rf"^## {re.escape(section)}\s*$", text, re.MULTILINE)) != 1:
            fail(path, f"requires exactly one '## {section}' section")

    records[path.resolve()] = fields

numbers = [path.name[:4] for path in records]
if len(numbers) != len(set(numbers)):
    errors.append("docs/adr: ADR numbers must be unique")

for path, fields in records.items():
    status = fields.get("Status")
    supersedes = link_target(fields.get("Supersedes", "none"))
    superseded_by = link_target(fields.get("Superseded by", "none"))

    if status == "superseded" and not superseded_by:
        fail(path, "superseded ADR requires a 'Superseded by' target")
    if status != "superseded" and superseded_by:
        fail(path, "only a superseded ADR may set 'Superseded by'")

    for field, target, reciprocal in (
        ("Supersedes", supersedes, "Superseded by"),
        ("Superseded by", superseded_by, "Supersedes"),
    ):
        if not target:
            continue
        target_path = (path.parent / target).resolve()
        if target_path not in records:
            fail(path, f"{field} target is not an ADR: {target}")
            continue
        reciprocal_target = link_target(records[target_path].get(reciprocal, "none"))
        if not reciprocal_target or (target_path.parent / reciprocal_target).resolve() != path:
            fail(path, f"{field} relationship is not reciprocal with {target}")

if errors:
    print("ADR verification failed:", file=sys.stderr)
    for error in errors:
        print(f"- {error}", file=sys.stderr)
    raise SystemExit(1)

print(f"ADR verification passed ({len(records)} decision records).")
PY

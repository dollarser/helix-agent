#!/usr/bin/env python3
"""Check explicit current ADR status claims; immutable historical snapshots are excluded."""
import re
from pathlib import Path


def mismatches(text: str, directory: Path):
    pattern = re.compile(r"\b(accepted|proposed|rejected|superseded)\s+\[ADR-[A-Z0-9]+-\d{3}\]\(([^)]+)\)")
    for number, line in enumerate(text.splitlines(), 1):
        # A deliberately marked historical statement is not a claim of current status.
        if "历史状态" in line or "当时" in line:
            continue
        for expected, target in pattern.findall(line):
            path = (directory / target.split("#", 1)[0]).resolve()
            if not path.is_file():
                yield number, f"missing ADR: {target}"
                continue
            actual = re.search(r"^Status: (\S+)$", path.read_text(), re.M)
            if actual is None or expected != actual[1]:
                yield number, f"{target}: claimed {expected}, actual {actual[1] if actual else 'missing'}"


def main():
    root = Path(__file__).resolve().parent.parent
    paths = [root / "AGENTS.md", root / "README.md"]
    for folder in ["architecture", "product", "security"]:
        paths.extend((root / "docs" / folder).glob("*.md"))
    for name in ["status.md", "roadmap.md", "implementation-guide.md"]:
        paths.append(root / "docs/development" / name)
    paths.extend((root / "docs/adr").rglob("README.md"))
    paths.extend((root / "docs/development/tasks").glob("*.md"))
    failures = []
    for path in paths:
        for line, message in mismatches(path.read_text(), path.parent):
            failures.append(f"{path.relative_to(root)}:{line}: {message}")
    if failures:
        raise SystemExit("\n".join(failures))
    print("Explicit current ADR status claims verified (historical snapshots excluded)")


if __name__ == "__main__":
    main()

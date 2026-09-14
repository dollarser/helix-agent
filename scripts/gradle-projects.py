#!/usr/bin/env python3
"""Read Helix's literal Gradle include list; fail on unsupported dynamic syntax."""
import argparse
import hashlib
import re
from pathlib import Path


def projects(text: str) -> list[str]:
    text = re.sub(r"/\*.*?\*/|//[^\n]*", "", text, flags=re.S)
    blocks = re.findall(r"\binclude\s*\(([^)]*)\)", text, re.S)
    if not blocks:
        raise ValueError("No literal include list in settings.gradle.kts")
    result = []
    for block in blocks:
        if re.sub(r'"(:[A-Za-z0-9_-]+)+"|[\s,]', "", block):
            raise ValueError("Dynamic include syntax requires updating the project inventory reader")
        result.extend(re.findall(r'"(:[A-Za-z0-9_:-]+)"', block))
    if not result or len(result) != len(set(result)):
        raise ValueError("Empty or duplicate project inventory")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    included = projects((root / "settings.gradle.kts").read_text())
    if args.snapshot:
        paths = ["gradle.lockfile", *(p[1:].replace(":", "/") + "/gradle.lockfile" for p in included)]
        for relative in sorted(paths):
            path = root / relative
            if not path.is_file():
                parser.exit(1, f"Missing dependency lock: {relative}\n")
            print(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {relative}")
    else:
        print("\n".join(included))


if __name__ == "__main__":
    main()

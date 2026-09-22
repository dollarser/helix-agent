#!/usr/bin/env python3
"""Restore a verified WIP snapshot after fast-forward, with explicit reviewed overlaps."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def git(root, *args, required=True):
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True, check=required)
    return result.stdout if result.returncode == 0 else None


def digest(content):
    return hashlib.sha256(content).hexdigest() if content is not None else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--expected-head", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if git(args.root, "rev-parse", "HEAD").decode().strip() != args.expected_head:
        raise ValueError("Unexpected destination HEAD")
    if git(args.root, "status", "--porcelain=v1", "--untracked-files=all"):
        raise ValueError("Destination must be clean before restoring WIP")
    if args.report.exists():
        raise ValueError("Refusing to overwrite restore report")
    snapshot = json.loads((args.snapshot / "manifest.json").read_text())
    reviewed = {"docs/README.md", "docs/development/status.md", "docs/development/next-work-plan.md"}
    keep_integrated = {"docs/research/ui-interaction-optimization.md"}
    plan = []
    for name, expected in snapshot["files"].items():
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Unsafe snapshot path")
        original = (args.snapshot / "files" / name).read_bytes() if expected else None
        if digest(original) != expected:
            raise ValueError(f"Snapshot checksum mismatch: {name}")
        base = git(args.root, "show", f"{snapshot['head']}:{name}", required=False)
        incoming = git(args.root, "show", f"HEAD:{name}", required=False)
        if name in reviewed:
            content, choice = (args.candidates / name).read_bytes(), "reviewed-document-merge"
        elif name in keep_integrated:
            if incoming is None:
                raise ValueError(f"Missing integrated document: {name}")
            content, choice = incoming, "retain-integrated-research-update"
        elif incoming == original:
            content, choice = incoming, "already-identical"
        elif incoming == base:
            content, choice = original, "restore-original-wip"
        else:
            raise ValueError(f"Unreviewed overlapping change: {name}")
        plan.append((name, content, {"path": name, "choice": choice, "originalSha256": expected,
                                      "restoredSha256": digest(content)}))
    # All conflicts and backup checks are resolved before the first write.
    for name, content, decision in plan:
        path = args.root / name
        if content is None:
            path.unlink(missing_ok=True)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
            if decision["choice"] == "restore-original-wip":
                shutil.copymode(args.snapshot / "files" / name, path)
    for name, content, _ in plan:
        path = args.root / name
        actual = path.read_bytes() if path.is_file() else None
        if actual != content:
            raise ValueError(f"Restoration verification failed: {name}")
    report = {"head": args.expected_head, "verified": True, "paths": [item[2] for item in plan]}
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"verified": True, "paths": len(plan)}))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Preserve and recheck non-ignored worktree edits before local integration."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def state(root):
    changed = git(root, "diff", "--name-only", "-z", "HEAD").split(b"\0")
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0")
    records = {}
    for raw in sorted(set(changed + untracked) - {b""}):
        name = raw.decode()
        path = root / name
        if path.is_symlink():
            raise ValueError(f"Review symbolic link before preserving: {name}")
        records[name] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    return {"head": git(root, "rev-parse", "HEAD").decode().strip(), "files": records,
            "status": git(root, "status", "--porcelain=v1", "-z").decode()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    current = state(args.source)
    manifest = args.output / "manifest.json"
    if args.verify:
        if json.loads(manifest.read_text()) != current:
            raise ValueError("Worktree changed since backup; preserve the new state first")
    else:
        args.output.mkdir(parents=True, exist_ok=False)
        for name, digest in current["files"].items():
            if digest is None:
                continue
            target = args.output / "files" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(args.source / name, target)
            if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                raise ValueError(f"Backup mismatch: {name}")
        (args.output / "working.patch").write_bytes(git(args.source, "diff", "--binary", "HEAD"))
        (args.output / "index.patch").write_bytes(git(args.source, "diff", "--binary", "--cached"))
        if state(args.source) != current:
            raise ValueError("Worktree changed during backup")
        manifest.write_text(json.dumps(current, indent=2) + "\n")
    print(json.dumps({"verified": True, "head": current["head"], "paths": len(current["files"])}))


if __name__ == "__main__":
    main()

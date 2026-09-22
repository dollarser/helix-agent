#!/usr/bin/env python3
"""Verify prior build archives and clone new build evidence without deleting anything."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path


CHUNK_SIZE = 1024 * 1024


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(CHUNK_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def files(root: Path) -> dict[str, tuple[int, str]]:
    result: dict[str, tuple[int, str]] = {}
    for path in sorted(root.rglob("*")):
        if path.is_file() and not path.is_symlink():
            relative = path.relative_to(root).as_posix()
            result[relative] = (path.stat().st_size, sha256(path))
    return result


def verify_existing_manifest(manifest_path: Path) -> dict[str, object]:
    checked = 0
    source_missing = 0
    target_missing = 0
    mismatched = 0
    mismatch_examples: list[dict[str, object]] = []
    worktrees: dict[str, dict[str, int]] = {}
    for line_number, line in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        checked += 1
        name = str(row["worktree"])
        stats = worktrees.setdefault(name, {"checked": 0, "ok": 0, "mismatch": 0})
        stats["checked"] += 1
        source = Path(str(row["source_root"])) / str(row["relative_path"])
        target = Path(str(row["target_root"])) / str(row["relative_path"])
        problem: str | None = None
        if not source.is_file():
            source_missing += 1
            problem = "source_missing"
        elif not target.is_file():
            target_missing += 1
            problem = "target_missing"
        else:
            source_size = source.stat().st_size
            target_size = target.stat().st_size
            source_hash = sha256(source)
            target_hash = sha256(target)
            if (
                source_size != int(row["bytes"])
                or target_size != int(row["bytes"])
                or source_hash != str(row["sha256"])
                or target_hash != str(row["sha256"])
                or source_hash != target_hash
            ):
                mismatched += 1
                problem = "size_or_sha256_mismatch"
        if problem is None:
            stats["ok"] += 1
        else:
            stats["mismatch"] += 1
            if len(mismatch_examples) < 20:
                mismatch_examples.append(
                    {
                        "line": line_number,
                        "worktree": name,
                        "relative_path": row["relative_path"],
                        "problem": problem,
                    }
                )
    return {
        "manifest": str(manifest_path),
        "entries": checked,
        "sourceMissing": source_missing,
        "targetMissing": target_missing,
        "mismatched": mismatched,
        "worktrees": worktrees,
        "mismatchExamples": mismatch_examples,
        "status": "verified" if source_missing + target_missing + mismatched == 0 else "attention",
    }


def clone_archive(name: str, source_root: Path, destination_root: Path, manifest) -> dict[str, object]:
    source = source_root / "build"
    archive_root = destination_root / name
    target = archive_root / "build"
    if not source.is_dir():
        return {"worktree": name, "status": "skipped", "reason": "missing build", "source_root": str(source)}
    if os.path.lexists(archive_root):
        raise RuntimeError(f"refusing to overwrite existing target: {archive_root}")
    archive_root.mkdir(parents=True)
    print(f"clone {source} -> {target}", file=sys.stderr, flush=True)
    subprocess.run(["cp", "-cR", f"{source}/.", str(target)], check=True)
    source_files = files(source)
    target_files = files(target)
    if set(source_files) != set(target_files):
        raise RuntimeError(f"file set mismatch for {name}")
    total_bytes = 0
    for relative in sorted(source_files):
        source_size, source_hash = source_files[relative]
        target_size, target_hash = target_files[relative]
        if (source_size, source_hash) != (target_size, target_hash):
            raise RuntimeError(f"content mismatch for {name}/{relative}")
        total_bytes += source_size
        manifest.write(
            json.dumps(
                {
                    "worktree": name,
                    "source_root": str(source),
                    "target_root": str(target),
                    "relative_path": relative,
                    "bytes": source_size,
                    "sha256": source_hash,
                },
                ensure_ascii=False,
                sort_keys=True,
            )
            + "\n"
        )
    return {
        "worktree": name,
        "status": "verified",
        "source_root": str(source),
        "target_root": str(target),
        "file_count": len(source_files),
        "bytes": total_bytes,
    }


def ignored_outside_build(worktree: Path) -> dict[str, object]:
    try:
        completed = subprocess.run(
            ["git", "-C", str(worktree), "status", "--ignored", "--short", "--untracked-files=normal"],
            check=True,
            capture_output=True,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as error:
        return {"worktree": str(worktree), "status": "unavailable", "error": str(error)}
    paths = []
    for line in completed.stdout.splitlines():
        if not line.startswith("!! "):
            continue
        path = line[3:].strip().rstrip("/")
        components = [component for component in path.split("/") if component]
        if path and "build" not in components:
            paths.append(path)
    return {
        "worktree": str(worktree),
        "status": "ok",
        "ignoredOutsideBuild": sorted(set(paths)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--existing-manifest", type=Path)
    parser.add_argument("--destination-root", type=Path, required=True)
    parser.add_argument("--archive", action="append", default=[], help="name=worktree-root")
    parser.add_argument("--scan-worktree", action="append", default=[])
    parser.add_argument("--refresh-ignored-only", action="store_true")
    args = parser.parse_args()

    destination = args.destination_root.resolve()
    if args.refresh_ignored_only:
        output = destination / "ignored-outside-build-full.json"
        if not destination.is_dir() or os.path.lexists(output):
            raise SystemExit(f"refusing to overwrite ignored report: {output}")
        ignored = [ignored_outside_build(Path(path).resolve()) for path in args.scan_worktree]
        output.write_text(json.dumps(ignored, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
        print(json.dumps(ignored, ensure_ascii=False, indent=2, sort_keys=True))
        return 0
    if args.existing_manifest is None or not args.archive or not args.scan_worktree:
        raise SystemExit("archive mode requires --existing-manifest, --archive, and --scan-worktree")
    destination.mkdir(parents=True, exist_ok=True)
    manifest_path = destination / "manifest.jsonl"
    summary_path = destination / "summary.json"
    verification_path = destination / "verification.json"
    ignored_path = destination / "ignored-outside-build.json"
    if any(os.path.lexists(path) for path in (manifest_path, summary_path, verification_path, ignored_path)):
        raise SystemExit(f"refusing to overwrite existing result in {destination}")

    archives: list[dict[str, object]] = []
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for spec in args.archive:
            if "=" not in spec:
                raise SystemExit(f"--archive must be name=worktree-root: {spec}")
            name, source = spec.split("=", 1)
            archives.append(clone_archive(name, Path(source).resolve(), destination, manifest))
    verification = verify_existing_manifest(args.existing_manifest.resolve())
    ignored = [ignored_outside_build(Path(path).resolve()) for path in args.scan_worktree]
    verification_path.write_text(json.dumps(verification, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    ignored_path.write_text(json.dumps(ignored, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    summary = {
        "status": "verified" if verification["status"] == "verified" and all(item["status"] == "verified" for item in archives) else "attention",
        "existingArchiveVerification": verification,
        "newArchives": archives,
        "ignoredOutsideBuild": ignored,
        "currentHxa216Archived": False,
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if summary["status"] == "verified" else 2


if __name__ == "__main__":
    raise SystemExit(main())

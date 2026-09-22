#!/usr/bin/env python3
"""Clone selected worktree build evidence and verify every regular file."""

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


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--destination-root", type=Path, required=True)
    parser.add_argument("--worktree", action="append", required=True)
    args = parser.parse_args()

    source_root = args.source_root.resolve()
    destination_root = args.destination_root.resolve()
    destination_root.mkdir(parents=True, exist_ok=True)
    manifest_path = destination_root / "manifest.jsonl"
    summary_path = destination_root / "summary.json"
    if os.path.lexists(manifest_path) or os.path.lexists(summary_path):
        raise SystemExit(f"refusing to overwrite existing manifest: {destination_root}")

    summaries: list[dict[str, object]] = []
    with manifest_path.open("w", encoding="utf-8") as manifest:
        for name in args.worktree:
            source = source_root / name / "build"
            archive_root = destination_root / name
            target = archive_root / "build"
            if not source.is_dir():
                summaries.append({"worktree": name, "status": "skipped", "reason": "missing build"})
                continue
            if os.path.lexists(archive_root):
                raise SystemExit(f"refusing to overwrite existing target: {archive_root}")

            archive_root.mkdir(parents=True)
            print(f"clone {source} -> {target}", file=sys.stderr, flush=True)
            subprocess.run(["cp", "-cR", f"{source}/.", str(target)], check=True)

            source_files = files(source)
            target_files = files(target)
            if set(source_files) != set(target_files):
                missing = sorted(set(source_files) - set(target_files))
                extra = sorted(set(target_files) - set(source_files))
                raise SystemExit(
                    f"file set mismatch for {name}: missing={missing[:5]} extra={extra[:5]}"
                )

            total_bytes = 0
            for relative in sorted(source_files):
                source_size, source_hash = source_files[relative]
                target_size, target_hash = target_files[relative]
                if (source_size, source_hash) != (target_size, target_hash):
                    raise SystemExit(f"content mismatch for {name}/{relative}")
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
            summary = {
                "worktree": name,
                "status": "verified",
                "source_root": str(source),
                "target_root": str(target),
                "file_count": len(source_files),
                "bytes": total_bytes,
            }
            summaries.append(summary)
            print(json.dumps(summary, ensure_ascii=False, sort_keys=True), file=sys.stderr, flush=True)

    summary_path.write_text(json.dumps(summaries, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    print(f"manifest={manifest_path}", file=sys.stderr)
    print(f"summary={summary_path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

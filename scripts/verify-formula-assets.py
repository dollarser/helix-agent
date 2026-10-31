#!/usr/bin/env python3
"""Verify reviewed, offline KaTeX distribution inputs. Never download or execute package code."""
import hashlib
import json
from pathlib import Path


def verify(root: Path) -> None:
    lock = json.loads((root / "config/math/katex.lock.json").read_text())
    assets = root / "app/src/main/assets/math"
    expected = lock["files"]
    actual = {p.relative_to(assets).as_posix() for p in assets.rglob("*") if p.is_file()}
    if actual != set(expected):
        raise ValueError("Formula asset inventory differs from the reviewed lock")
    for name, digest in expected.items():
        if hashlib.sha256((assets / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Formula asset hash mismatch: {name}")
    license_file = root / "app/src/main/assets/licenses/KaTeX-MIT.txt"
    if hashlib.sha256(license_file.read_bytes()).hexdigest() != lock["licenseSha256"]:
        raise ValueError("Formula license changed")


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[1])
    print("Pinned offline formula assets and license verified.")

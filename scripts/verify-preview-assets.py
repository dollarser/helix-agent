#!/usr/bin/env python3
"""Verify reviewed, offline preview distribution inputs. Never download or execute package code."""
import hashlib
import json
from pathlib import Path


def verify_math(root: Path) -> None:
    lock = json.loads((root / "config/math/katex.lock.json").read_text())
    assets = root / "ui/shared/src/main/assets/math"
    expected = lock["files"]
    actual = {p.relative_to(assets).as_posix() for p in assets.rglob("*") if p.is_file()}
    if actual != set(expected):
        raise ValueError("Formula asset inventory differs from the reviewed lock")
    for name, digest in expected.items():
        if hashlib.sha256((assets / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Formula asset hash mismatch: {name}")
    license_file = root / "ui/shared/src/main/assets/licenses/KaTeX-MIT.txt"
    if hashlib.sha256(license_file.read_bytes()).hexdigest() != lock["licenseSha256"]:
        raise ValueError("Formula license changed")


def verify_mermaid(root: Path) -> None:
    lock = json.loads((root / "config/mermaid/mermaid.lock.json").read_text())
    assets = root / "ui/shared/src/main/assets/mermaid"
    actual = {p.relative_to(root).as_posix() for p in assets.rglob("*") if p.is_file()}
    expected_assets = {p for p in lock["files"] if p.startswith("ui/shared/src/main/assets/mermaid/")}
    if actual != expected_assets:
        raise ValueError("Diagram asset inventory differs from the reviewed lock")
    for name, digest in lock["files"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError("Diagram asset/license hash mismatch: " + name)


def verify_svg(root: Path) -> None:
    lock = json.loads((root / "config/svg/dompurify.lock.json").read_text())
    assets = root / "ui/shared/src/main/assets/svg"
    if {p.name for p in assets.iterdir() if p.is_file()} != {"purify.min.js", "sanitize.js"}:
        raise ValueError("SVG asset inventory differs from the reviewed sources")
    for name, digest in lock["files"].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
            raise ValueError("SVG asset/license hash mismatch: " + name)


def verify(root: Path) -> None:
    verify_math(root)
    verify_mermaid(root)
    verify_svg(root)


if __name__ == "__main__":
    verify(Path(__file__).resolve().parents[1])
    print("Pinned offline formula/diagram assets and licenses verified.")

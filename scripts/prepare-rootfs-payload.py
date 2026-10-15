#!/usr/bin/env python3
"""Encode the locked raw RootFS as one deterministic, bounded XZ asset."""
import argparse
import hashlib
import json
import lzma
import os
from pathlib import Path
import tempfile

MAX_RAW_BYTES = 256 * 1024 * 1024
MAX_ENCODED_BYTES = 64 * 1024 * 1024


def prepare(assets, destination):
    lock = json.loads((assets / "runtime-lock.json").read_text())
    component = next(item for item in lock["components"] if item["id"] == "alpine-rootfs")
    name = component["url"].rsplit("/", 1)[-1].removesuffix(".gz")
    if Path(name).name != name or not name.endswith(".tar"):
        raise ValueError("Expected a locked raw tar filename")
    raw = assets / "rootfs" / name
    if not 0 < component["size"] <= MAX_RAW_BYTES or raw.stat().st_size != component["size"]:
        raise ValueError("RootFS size differs from lock or exceeds the payload limit")
    target = destination / "runtime" / "rootfs" / (name + ".xz")
    target.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".rootfs-", dir=target.parent)
    try:
        digest = hashlib.sha256()
        count = 0
        with os.fdopen(descriptor, "wb") as output, raw.open("rb") as source:
            with lzma.LZMAFile(output, "wb", format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=6) as encoded:
                while chunk := source.read(64 * 1024):
                    count += len(chunk)
                    if count > component["size"]:
                        raise ValueError("RootFS grew during packaging")
                    digest.update(chunk)
                    encoded.write(chunk)
        if count != component["size"] or digest.hexdigest() != component["sha256"]:
            raise ValueError("RootFS bytes differ from runtime-lock.json")
        if Path(temporary).stat().st_size > MAX_ENCODED_BYTES:
            raise ValueError("Encoded RootFS exceeds the runtime input limit")
        # Verify the emitted stream as well as the original input before publishing.
        with lzma.open(temporary, "rb") as decoded:
            if hashlib.file_digest(decoded, "sha256").hexdigest() != component["sha256"]:
                raise ValueError("Encoded RootFS failed round-trip verification")
        os.replace(temporary, target)
        return target
    finally:
        Path(temporary).unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("assets", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    result = prepare(args.assets, args.destination)
    print(f"RootFS XZ payload verified: {result.name} ({result.stat().st_size} bytes)")

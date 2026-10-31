#!/usr/bin/env python3
"""Reproduce the reviewed Mermaid asset using explicit, verified offline inputs.

Download the source/tool archives named in config/mermaid/mermaid.lock.json
separately. This script downloads nothing and runs no package installation hooks.
"""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import platform
import subprocess
import tarfile
import tempfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def build(source_archive, tool_archive, destination, runtime_archive=None):
    root = Path(__file__).resolve().parents[1]
    lock = json.loads((root / "config/mermaid/mermaid.lock.json").read_text())
    config = lock["build"]
    key = platform.system() + "-" + platform.machine()
    tool = config["toolPackages"][key]
    packages = [(source_archive, lock), (tool_archive, tool)]
    if runtime_archive is not None:
        packages.append((runtime_archive, config["runtime"]))
    for path, spec in packages:
        data = path.read_bytes()
        if digest(data) != spec["archiveSha256"] or (
            "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode() != spec["integrity"]
        ):
            raise ValueError("Archive integrity mismatch: " + path.name)
    with tempfile.TemporaryDirectory(prefix="helix-mermaid-") as temp:
        directory = Path(temp)
        with tarfile.open(source_archive) as archive:
            source = archive.extractfile(config["sourceMember"]).read()
        if digest(source) != config["sourceSha256"]:
            raise ValueError("Mermaid source changed")
        with tarfile.open(tool_archive) as archive:
            executable = archive.extractfile("package/bin/esbuild").read()
        if digest(executable) != tool["executableSha256"]:
            raise ValueError("Build tool changed")
        binary = directory / "esbuild"
        binary.write_bytes(executable)
        binary.chmod(0o700)
        version = subprocess.check_output([str(binary), "--version"], text=True).strip()
        if version != config["version"]:
            raise ValueError("Unexpected build tool version")
        source_path = directory / "input.js"
        source_path.write_bytes(source)
        output = directory / "output.js"
        subprocess.run([str(binary), str(source_path), *config["arguments"], "--outfile=" + str(output)], check=True)
        if runtime_archive is None:
            raise ValueError("Explicit core-js runtime archive required")
        with tarfile.open(runtime_archive) as archive:
            runtime = archive.extractfile(config["runtime"]["member"]).read()
        if digest(runtime) != config["runtime"]["sourceSha256"]:
            raise ValueError("Runtime source changed")
        data = runtime + b"\n;\n" + output.read_bytes()
        expected = lock["files"]["app/src/main/assets/mermaid/mermaid.tiny.js"]
        if digest(data) != expected:
            raise ValueError("Generated asset differs from reviewed output")
        destination.write_bytes(data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", type=Path, required=True)
    parser.add_argument("--tool-archive", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--runtime-archive", type=Path, required=True)
    args = parser.parse_args()
    build(args.source_archive, args.tool_archive, args.output, args.runtime_archive)

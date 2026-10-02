#!/usr/bin/env python3
"""Measure complete Android FFmpeg payloads, not .a/debug/source archives or only the CLI entry."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build/ffmpeg-lite-size-2026-10-01"
LIMIT = 25_000_000
LIBRARIES = {"libavcodec.so", "libavformat.so", "libavfilter.so", "libavutil.so", "libswscale.so", "libswresample.so"}
SYSTEM = {"libc.so", "libm.so", "libdl.so", "libz.so", "liblog.so", "libandroid.so", "libmediandk.so"}


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def make_zip(path: Path, sources: list[tuple[Path, str]], compression: int) -> int:
    with zipfile.ZipFile(path, "w", compression=compression, compresslevel=9) as archive:
        for source, name in sorted(sources, key=lambda item: item[1]):
            info = zipfile.ZipInfo(name, (2026, 10, 1, 0, 0, 0))
            info.compress_type = compression
            info.external_attr = (0o100755 if ".so" in name or name.endswith(("/ffmpeg", "/ffprobe")) else 0o100644) << 16
            archive.writestr(info, source.read_bytes())
    return path.stat().st_size


def measure(target: str) -> dict:
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    tools = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin"
    prefix = OUT / "install" / target
    library_files = sorted(prefix.glob("lib/*.so"))
    if {p.name for p in library_files} != LIBRARIES:
        raise RuntimeError(f"Unexpected library set: {[p.name for p in library_files]}")
    stage = OUT / "payload" / target
    stage.mkdir(parents=True, exist_ok=True)
    sources: list[tuple[Path, str]] = []
    rows = []
    for original in library_files + [prefix / "bin/ffmpeg", prefix / "bin/ffprobe"]:
        if not original.is_file():
            raise RuntimeError(f"Missing runtime file {original}")
        dest = stage / original.name
        shutil.copy2(original, dest)
        subprocess.run([str(tools / "llvm-strip"), "--strip-unneeded", str(dest)], check=True)
        elf = subprocess.check_output([str(tools / "llvm-readelf"), "-h", "-lW", "-dW", str(dest)], text=True)
        (stage / (dest.name + ".elf.txt")).write_text(elf)
        if "AArch64" not in elf and target == "arm64-v8a":
            raise RuntimeError("Not an Android AArch64 ELF")
        if "Advanced Micro Devices X86-64" not in elf and target == "x86_64":
            raise RuntimeError("Not an x86_64 ELF")
        alignments = [int(line.split()[-1], 16) for line in elf.splitlines() if line.strip().startswith("LOAD ")]
        if not alignments or any(value < 16384 for value in alignments):
            raise RuntimeError(f"Non-16KiB ELF alignment: {dest.name}: {alignments}")
        needed = re.findall(r"\(NEEDED\).*?\[([^]]+)\]", elf)
        if set(needed) - SYSTEM - LIBRARIES:
            raise RuntimeError(f"Unbundled dependency: {dest.name}: {needed}")
        if "/Users/" in elf:
            raise RuntimeError(f"Host library path in ELF metadata: {dest.name}")
        role = "libraries" if dest.suffix == ".so" else "tools"
        entry = f"lib/{target}/{dest.name}" if role == "libraries" else f"tools/{target}/{dest.name}"
        sources.append((dest, entry))
        rows.append({"file": dest.name, "role": role, "bytes": dest.stat().st_size,
                     "sha256": sha(dest), "needed": needed, "load_alignments": alignments})
    conf = (OUT / "obj" / target / "config_components.h").read_text()
    flags = (OUT / "obj" / target / "config.h").read_text()
    for name in ("H264_DECODER", "HEVC_DECODER", "AAC_ENCODER", "PNG_ENCODER", "MJPEG_ENCODER",
                 "H264_MEDIACODEC_ENCODER", "HEVC_MEDIACODEC_ENCODER", "MOV_DEMUXER", "MP4_MUXER", "SCALE_FILTER"):
        if f"#define CONFIG_{name} 1" not in conf:
            raise RuntimeError(f"Required capability not compiled: {name}")
    for name in ("GPL", "NONFREE", "VERSION3", "NETWORK", "AVDEVICE"):
        if f"#define CONFIG_{name} 0" not in flags:
            raise RuntimeError(f"Unexpected enablement: {name}")
    enabled = re.findall(r"#define CONFIG_([A-Z0-9_]+) 1", conf)
    packages = OUT / "packages"
    packages.mkdir(exist_ok=True)
    sizes = {}
    for label, selected in (("libraries", [pair for pair in sources if pair[1].startswith("lib/")]),
                            ("libraries-and-cli", sources)):
        for kind, compression in (("deflated", zipfile.ZIP_DEFLATED), ("stored", zipfile.ZIP_STORED)):
            name = f"ffmpeg-lite-{target}-{label}-{kind}.zip"
            path = packages / name
            size = make_zip(path, selected, compression)
            sizes[f"{label}_{kind}"] = {"bytes": size, "sha256": sha(path), "file": str(path.relative_to(ROOT))}
            if kind == "stored":
                aligned = path.with_name(path.stem + "-16k.zip")
                aligner = sdk / "build-tools/36.0.0/zipalign"
                subprocess.run([str(aligner), "-f", "-P", "16", "4", str(path), str(aligned)], check=True)
                subprocess.run([str(aligner), "-c", "-P", "16", "4", str(aligned)], check=True)
                sizes[f"{label}_stored_aligned"] = {"bytes": aligned.stat().st_size, "sha256": sha(aligned),
                                                     "file": str(aligned.relative_to(ROOT))}
    legal = OUT / "source/ffmpeg-9.0.2"
    bundle = list(sources) + [(legal / "COPYING.LGPLv2.1", "LICENSES/COPYING.LGPLv2.1"),
                              (legal / "LICENSE.md", "LICENSES/FFmpeg-LICENSE.md"),
                              (OUT / "source-provenance.json", "source-provenance.json"),
                              (OUT / "obj" / target / "configure-argv.json", "configure-argv.json")]
    archive = packages / f"ffmpeg-9.0.2-lite-{target}.zip"
    make_zip(archive, bundle, zipfile.ZIP_DEFLATED)
    size = sum(row["bytes"] for row in rows)
    result = {"target": target, "threshold_bytes": LIMIT, "runtime_total_bytes": size,
              "libraries_bytes": sum(row["bytes"] for row in rows if row["role"] == "libraries"),
              "under_threshold_uncompressed": size < LIMIT,
              "complete_stored_aligned_under_threshold": sizes["libraries-and-cli_stored_aligned"]["bytes"] < LIMIT,
              "artifacts": rows, "packages": sizes, "bundle": str(archive.relative_to(ROOT)),
              "bundle_bytes": archive.stat().st_size, "enabled_components": enabled,
              "elf_static_validation": "passed", "android_device_execution": "not_requested",
              "apk_integration": "not_performed", "note": "ZIP payload sizes are measured; not a signed integrated Helix APK delta."}
    (OUT / f"size-{target}.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=("arm64-v8a", "x86_64"), default="arm64-v8a")
    summary = measure(parser.parse_args().target)
    print(json.dumps({key: value for key, value in summary.items() if key != "enabled_components"}, indent=2))

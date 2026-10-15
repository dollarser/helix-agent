#!/usr/bin/env python3
"""Isolated FFmpeg size experiment. Never edits the app, installs a device, or changes the host SDK."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build/ffmpeg-lite-size-2026-10-01"
VERSION = "9.0.2"
NDK_VERSION = "28.2.13676358"
SIGNER = "FCF986EA15E6E293A5644F10B4322F04D67658D8"
SOURCE = OUT / "source" / f"ffmpeg-{VERSION}"
SOURCE_SHA256 = "8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"
COMPONENTS = {
    "decoder": "h264,hevc,mpeg4,vp8,vp9,aac,mp3,flac,opus,vorbis,pcm_s16le,pcm_s24le,pcm_s32le,pcm_f32le,mjpeg,png,bmp,gif,webp,rawvideo",
    "encoder": "aac,flac,pcm_s16le,pcm_s24le,pcm_f32le,mjpeg,png,bmp,gif,mpeg4,rawvideo,wrapped_avframe",
    "demuxer": "mov,matroska,avi,mpegts,aac,mp3,wav,flac,ogg,image2,image2pipe,gif,h264,hevc,rawvideo,pcm_s16le",
    "muxer": "mp4,mov,matroska,webm,avi,mpegts,adts,mp3,wav,flac,ogg,image2,image2pipe,gif,h264,hevc,rawvideo,null,framehash",
    "parser": "h264,hevc,mpeg4video,vp8,vp9,aac,mpegaudio,flac,opus,vorbis,png,mjpeg,webp,gif",
    "bsf": "aac_adtstoasc,h264_mp4toannexb,hevc_mp4toannexb,extract_extradata,null",
    "filter": "aformat,anull,aresample,atrim,asetpts,atempo,volume,afade,amix,asplit,scale,crop,pad,transpose,hflip,vflip,fps,trim,setpts,format,null,copy,split,concat,thumbnail,palettegen,paletteuse,setsar,rotate,overlay",
    "protocol": "file,pipe,fd",
}


def run(argv: list[str], *, cwd: Path | None = None, log: str,
        env: dict[str, str] | None = None) -> None:
    target = OUT / "logs" / log
    target.parent.mkdir(parents=True, exist_ok=True)
    print(json.dumps({"phase": log, "started": True}), flush=True)
    with target.open("w") as handle:
        result = subprocess.run(argv, cwd=cwd or ROOT, env=env, stdout=handle, stderr=subprocess.STDOUT)
    if result.returncode:
        print(target.read_text(errors="replace")[-10000:], flush=True)
        raise RuntimeError(f"{log}: exit={result.returncode}; see {target.relative_to(ROOT)}")
    print(json.dumps({"phase": log, "completed": True}), flush=True)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def prepare() -> None:
    download = OUT / "downloads"
    download.mkdir(parents=True, exist_ok=True)
    urls = {
        f"ffmpeg-{VERSION}.tar.xz": f"https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz",
        f"ffmpeg-{VERSION}.tar.xz.asc": f"https://ffmpeg.org/releases/ffmpeg-{VERSION}.tar.xz.asc",
        "ffmpeg-devel.asc": "https://ffmpeg.org/ffmpeg-devel.asc",
    }
    for name, url in urls.items():
        path = download / name
        if not path.exists():
            partial = path.with_suffix(path.suffix + ".partial")
            run(["curl", "--fail", "--location", "--proto", "=https", "--tlsv1.2", "--connect-timeout", "20",
                 "--max-time", "240", "--retry", "2", "--output", str(partial), url], log=f"download-{name}.log")
            partial.replace(path)
    gnupg = OUT / "gnupg"
    gnupg.mkdir(mode=0o700, exist_ok=True)
    gnupg.chmod(0o700)
    gpg = ["gpg", "--homedir", str(gnupg), "--batch", "--no-auto-key-retrieve"]
    run(gpg + ["--import", str(download / "ffmpeg-devel.asc")], log="source-key-import.log")
    archive = download / f"ffmpeg-{VERSION}.tar.xz"
    run(gpg + ["--status-fd", "1", "--verify", str(archive) + ".asc", str(archive)], log="source-signature.log")
    verification = (OUT / "logs/source-signature.log").read_text()
    if f"[GNUPG:] VALIDSIG {SIGNER} " not in verification:
        raise RuntimeError("Unexpected FFmpeg signing fingerprint")
    if not SOURCE.exists():
        SOURCE.parent.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive, "r:xz") as source:
            for entry in source.getmembers():
                if entry.name != f"ffmpeg-{VERSION}" and not entry.name.startswith(f"ffmpeg-{VERSION}/"):
                    raise RuntimeError("Unexpected source archive member")
            source.extractall(SOURCE.parent, filter="data")
    metadata = {"version": VERSION, "source_url": urls[archive.name], "source_sha256": digest(archive),
                "signing_fingerprint": SIGNER, "verified_signature": True,
                "repository_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "observed_epoch": int(time.time())}
    (OUT / "source-provenance.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata), flush=True)


def build(target: str, jobs: int) -> None:
    if not SOURCE.is_dir():
        raise RuntimeError("Run prepare first")
    if digest(OUT / "downloads" / f"ffmpeg-{VERSION}.tar.xz") != SOURCE_SHA256:
        raise RuntimeError("Pinned source archive changed")
    work = OUT / "obj" / target
    prefix = OUT / "install" / target
    work.mkdir(parents=True, exist_ok=True)
    flags = [
        "--disable-autodetect", "--disable-everything", "--disable-doc", "--disable-debug",
        "--disable-network", "--disable-avdevice", "--disable-programs", "--enable-ffmpeg", "--enable-ffprobe",
        "--disable-gpl", "--disable-nonfree", "--disable-version3", "--disable-static", "--enable-shared",
        "--disable-symver", "--enable-small", "--enable-pic", "--enable-pthreads", "--enable-zlib",
        f"--prefix={prefix}", "--extra-cflags=-Os -ffunction-sections -fdata-sections -fstack-protector-strong",
    ]
    flags += [f"--enable-{key}={value}" for key, value in COMPONENTS.items()]
    env = dict(os.environ)
    env.update({"LC_ALL": "C", "SOURCE_DATE_EPOCH": "1789689600", "ZERO_AR_DATE": "1"})
    env.pop("PKG_CONFIG_PATH", None)
    env["PKG_CONFIG_LIBDIR"] = str(OUT / "no-pkg-config")
    ndk = None
    if target != "host":
        sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
        ndk = sdk / "ndk" / NDK_VERSION
        host_tag = "darwin-x86_64" if platform.system() == "Darwin" else "linux-x86_64"
        tools = ndk / "toolchains/llvm/prebuilt" / host_tag / "bin"
        arch, triple = {"arm64-v8a": ("aarch64", "aarch64-linux-android"),
                        "x86_64": ("x86_64", "x86_64-linux-android")}[target]
        cc = tools / f"{triple}29-clang"
        if not cc.is_file():
            raise RuntimeError(f"Missing pinned NDK compiler: {cc}")
        flags += ["--target-os=android", "--enable-cross-compile", f"--arch={arch}",
                  f"--cc={cc}", f"--cxx={tools / (triple + '29-clang++')}", f"--ar={tools / 'llvm-ar'}",
                  f"--ranlib={tools / 'llvm-ranlib'}", f"--strip={tools / 'llvm-strip'}",
                  f"--nm={tools / 'llvm-nm'}", "--enable-jni", "--enable-mediacodec",
                  "--enable-decoder=h264_mediacodec,hevc_mediacodec",
                  "--enable-encoder=h264_mediacodec,hevc_mediacodec",
                  "--extra-ldflags=-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,-z,relro -Wl,-z,now"]
        if target == "x86_64":
            if shutil.which("nasm") is None:
                raise RuntimeError("x86_64 optimized build requires nasm; do not silently disable SIMD")
    else:
        flags += ["--cc=clang", "--extra-ldflags=-Wl,-dead_strip"]
    (work / "configure-argv.json").write_text(json.dumps(flags, indent=2) + "\n")
    started = time.monotonic()
    run([str(SOURCE / "configure")] + flags, cwd=work, env=env, log=f"configure-{target}.log")
    run(["make", f"-j{jobs}"], cwd=work, env=env, log=f"make-{target}.log")
    run(["make", "install"], cwd=work, env=env, log=f"install-{target}.log")
    metadata = {"target": target, "ndk_version": NDK_VERSION if ndk else None,
                "api": 29 if ndk else None, "elapsed_seconds": round(time.monotonic() - started, 2),
                "configure_flags": flags, "requested_components": COMPONENTS, "jobs": jobs}
    (work / "build-result.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps({"built": target, "elapsed_seconds": metadata["elapsed_seconds"],
                      "prefix": str(prefix.relative_to(ROOT))}), flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "build"))
    parser.add_argument("--target", choices=("arm64-v8a", "x86_64", "host"), default="arm64-v8a")
    parser.add_argument("--jobs", type=int, default=6)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 12:
        parser.error("jobs must be 1..12")
    if args.action == "prepare":
        prepare()
    else:
        build(args.target, args.jobs)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Execute synthetic functional checks on the host companion build, NOT on Android or a user file."""
from __future__ import annotations

import array
import hashlib
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "build/ffmpeg-lite-size-2026-10-01"
BIN = OUT / "install/host/bin"
WORK = OUT / "host-smoke"
RESULTS: list[dict] = []


def command(name: str, executable: str | Path, args: list[str], *, success: bool | None = True) -> str:
    result = subprocess.run([str(executable)] + args, cwd=WORK, capture_output=True, text=True, timeout=60)
    (WORK / f"{name}.stdout.log").write_text(result.stdout)
    (WORK / f"{name}.stderr.log").write_text(result.stderr)
    if success and result.returncode != 0:
        raise AssertionError(f"{name}: exit {result.returncode}: {result.stderr[-4000:]}")
    if success is False and result.returncode == 0:
        raise AssertionError(f"{name}: expected rejection")
    return result.stdout + result.stderr


def ff(name: str, *args: str, success: bool | None = True) -> str:
    return command(name, BIN / "ffmpeg", ["-hide_banner", "-loglevel", "error", "-nostdin", *args], success=success)


def probe(name: str, path: str) -> dict:
    return json.loads(command(name, BIN / "ffprobe", ["-v", "error", "-show_streams", "-show_format", "-of", "json", path]))


def record(name: str, check) -> None:
    started = time.monotonic()
    check()
    RESULTS.append({"name": name, "status": "passed", "seconds": round(time.monotonic() - started, 3)})
    print(json.dumps(RESULTS[-1]), flush=True)


def main() -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    width, height = 64, 48
    raw = bytearray()
    for frame in range(20):
        raw.extend(((x + y + frame * 7) % 220 + 16) for y in range(height) for x in range(width))
        raw.extend(bytes([96 + frame % 20]) * (width * height // 4))
        raw.extend(bytes([160 - frame % 20]) * (width * height // 4))
    (WORK / "input.yuv").write_bytes(raw)
    samples = array.array("h", (int(10000 * math.sin(2 * math.pi * 440 * i / 48000)) for i in range(96000)))
    if sys.byteorder != "little":
        samples.byteswap()
    (WORK / "input.pcm").write_bytes(samples.tobytes())

    def capabilities():
        enc = command("encoders", BIN / "ffmpeg", ["-hide_banner", "-encoders"])
        dec = command("decoders", BIN / "ffmpeg", ["-hide_banner", "-decoders"])
        for codec in ("aac", "mjpeg", "png", "mpeg4"):
            assert codec in enc
        for codec in ("h264", "hevc", "vp9", "opus"):
            assert codec in dec
        protocols = command("protocols", BIN / "ffmpeg", ["-hide_banner", "-protocols"])
        assert "http" not in protocols and "tcp" not in protocols and "file" in protocols
        assert "libx264" not in enc and "libx265" not in enc

    def mp4():
        ff("encode-mp4", "-y", "-f", "rawvideo", "-pixel_format", "yuv420p", "-video_size", "64x48",
           "-framerate", "10", "-i", "input.yuv", "-f", "s16le", "-ar", "48000", "-ac", "1", "-i", "input.pcm",
           "-c:v", "mpeg4", "-q:v", "5", "-c:a", "aac", "-b:a", "64k", "-shortest", "input.mp4")
        data = probe("probe-mp4", "input.mp4")
        assert {s["codec_name"] for s in data["streams"]} == {"mpeg4", "aac"}
        assert 1.9 <= float(data["format"]["duration"]) <= 2.1

    def jpeg():
        ff("jpeg-transform", "-y", "-i", "input.mp4", "-vf", "crop=48:32:8:8,scale=96:64,transpose=1",
           "-frames:v", "1", "-q:v", "3", "preview.jpg")
        stream = probe("probe-jpeg", "preview.jpg")["streams"][0]
        assert (stream["width"], stream["height"]) == (64, 96)

    def png():
        ff("png-frame", "-y", "-ss", "0.5", "-i", "input.mp4", "-frames:v", "1", "frame.png")
        assert (WORK / "frame.png").read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
        stream = probe("probe-png", "frame.png")["streams"][0]
        assert (stream["width"], stream["height"]) == (64, 48)

    def audio():
        ff("audio-extract", "-y", "-i", "input.mp4", "-vn", "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", "audio.wav")
        stream = probe("probe-wave", "audio.wav")["streams"][0]
        assert stream["codec_name"] == "pcm_s16le" and stream["sample_rate"] == "16000"

    def remux():
        ff("remux", "-y", "-i", "input.mp4", "-map", "0", "-c", "copy", "remux.mkv")
        streams = probe("probe-mkv", "remux.mkv")["streams"]
        assert {s["codec_name"] for s in streams} == {"mpeg4", "aac"}

    def gif():
        ff("gif", "-y", "-i", "input.mp4", "-vf", "fps=5,scale=48:36", "-an", "animation.gif")
        assert (WORK / "animation.gif").read_bytes().startswith(b"GIF")
        assert probe("probe-gif", "animation.gif")["streams"][0]["width"] == 48

    def malformed():
        (WORK / "bad.mp4").write_bytes(b"not a media file")
        ff("reject-malformed", "-i", "bad.mp4", "-f", "null", "-", success=False)

    def protect_output():
        file = WORK / "preview.jpg"
        before = hashlib.sha256(file.read_bytes()).hexdigest()
        # FFmpeg main maps AVERROR_EXIT (including user refusal) to exit 0. Check real non-overwrite,
        # not the incorrect assumption that every refusal exits nonzero; see fftools/ffmpeg.c:1047.
        diagnostic = ff("reject-overwrite", "-n", "-i", "input.mp4", "-frames:v", "1", "preview.jpg", success=None)
        assert "already exists. Exiting." in diagnostic
        assert hashlib.sha256(file.read_bytes()).hexdigest() == before

    def software_decode(codec: str):
        # The pre-existing full host FFmpeg ONLY generates fixtures; the freshly compiled lite executable decodes them.
        full = shutil.which("ffmpeg")
        if full is None:
            raise RuntimeError("Pre-existing fixture generator unavailable")
        encoder = "libx264" if codec == "h264" else "libx265"
        args = ["-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pixel_format", "yuv420p",
                "-video_size", "64x48", "-framerate", "10", "-i", "input.yuv", "-an", "-c:v", encoder,
                "-preset", "ultrafast", "-threads", "1"]
        if codec == "hevc":
            args += ["-x265-params", "pools=none:frame-threads=1:log-level=error"]
        command(f"fixture-{codec}", full, args + [f"{codec}.mp4"])
        ff(f"decode-{codec}", "-i", f"{codec}.mp4", "-an", "-f", "framehash", "-y", f"{codec}.frames")
        frames = [line for line in (WORK / f"{codec}.frames").read_text().splitlines() if not line.startswith("#")]
        assert len(frames) == 20

    record("compiled_feature_and_no_network_inventory", capabilities)
    record("mpeg4_aac_mp4_encode_and_probe", mp4)
    record("crop_scale_rotate_jpeg", jpeg)
    record("seek_extract_png", png)
    record("audio_extract_resample_wave", audio)
    record("stream_copy_mp4_to_matroska", remux)
    record("animated_gif_export", gif)
    record("malformed_input_rejected", malformed)
    record("existing_output_not_overwritten", protect_output)
    record("h264_software_decode_20_frames", lambda: software_decode("h264"))
    record("hevc_software_decode_20_frames", lambda: software_decode("hevc"))
    summary = {"platform": "macOS host companion with the same software component whitelist",
               "test_count": len(RESULTS), "failed": 0, "tests": RESULTS,
               "android_execution": "not_requested", "mediacodec_execution": "not_requested"}
    (OUT / "host-smoke-results.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"passed": len(RESULTS), "failed": 0, "android_execution": "not_requested"}))


if __name__ == "__main__":
    main()

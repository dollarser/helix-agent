#!/usr/bin/env python3
"""Build and run the P5 SGLang Harness baseline on one exclusively owned API36 emulator."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import urllib.request


ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/p5-sglang-harness-baseline")
    parser.add_argument("--avd", default="Helix_HXA210_API36")
    parser.add_argument("--emulator-port", type=int, default=5670)
    args = parser.parse_args()
    out = ROOT / args.output
    if out.exists():
        parser.error(f"output already exists: {out}")
    with urllib.request.urlopen("http://127.0.0.1:30008/v1/models", timeout=10) as response:
        models = json.load(response)
    if not models.get("data") or models["data"][0].get("id") != "Qwen3.8-27B":
        parser.error("localhost:30008 must expose Qwen3.8-27B")
    subprocess.run(
        ["./gradlew", ":app:assembleDeveloperDebug", ":app:assembleDeveloperDebugAndroidTest"],
        cwd=ROOT,
        check=True,
        timeout=1200,
    )
    command = [
        sys.executable,
        "scripts/run-owned-emulator.py",
        "--avd",
        args.avd,
        "--port",
        str(args.emulator_port),
        "--memory-mb",
        "4096",
        "--cores",
        "4",
        "--density-dpi",
        "400",
        "--apk",
        "app/build/outputs/apk/developer/debug/app-developer-debug.apk",
        "--test-apk",
        "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk",
        "--classes",
        "com.helix.app.provider.SglangUiSmokeTest",
        "--runner",
        "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
        "--output",
        args.output,
        "--timeout",
        "900",
        "--instrument-arg",
        "realSelfHosted=true",
        "--after-script",
        "scripts/debug/2026-09-28/run-p5-sglang-suites.py",
        "--raw-results",
    ]
    subprocess.run(command, cwd=ROOT, check=True, timeout=3600)


if __name__ == "__main__":
    main()

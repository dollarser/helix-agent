#!/usr/bin/env python3
"""HXA-191 follow-up on the owned serial: the storage connected query test.

Invoked by scripts/run-owned-emulator.py as
`python3 <this> <serial> <output-dir>` after the app instrumentation passes,
before the emulator is torn down. Builds and runs the Room-level search query
test on the same device, then fails the run unless a fresh result XML shows
all selected methods passing for com.helix.core.storage.SessionSearchQueryDeviceTest.
"""
import os
import subprocess
import sys
import time
from pathlib import Path
import importlib.util

CLASS = "com.helix.core.storage.SessionSearchQueryDeviceTest"


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(f"usage: {sys.argv[0]} <serial> <output-dir>")
    serial, output_dir = sys.argv[1], sys.argv[2]
    repo = Path(__file__).resolve().parents[3]
    results_root = repo / "core/storage/build/outputs/androidTest-results/connected"
    # Force a fresh connected run: the AGP connected-test task can otherwise be
    # satisfied from a prior run's result XML, which would defeat the freshness
    # check below. Deleting prior result XMLs makes a stale pass impossible.
    if results_root.exists():
        for prior in results_root.glob("**/TEST-*.xml"):
            prior.unlink(missing_ok=True)
    started = time.time()
    env = dict(os.environ, ANDROID_SERIAL=serial)
    log_path = Path(output_dir) / "storage-connected.log"
    with log_path.open("w") as log:
        result = subprocess.run(
            [
                str(repo / "gradlew"),
                ":core:storage:assembleDebugAndroidTest",
                ":core:storage:connectedDebugAndroidTest",
                f"-Pandroid.testInstrumentationRunnerArguments.class={CLASS}",
            ],
            cwd=repo,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
        )
    if result.returncode != 0:
        tail = "\n".join(log_path.read_text().splitlines()[-40:])
        raise SystemExit(f"storage connected gradle failed (exit {result.returncode}); tail:\n{tail}")

    # AGP 9.x names the connected result XML by device + package
    # (e.g. TEST-Helix191_API36(AVD) - 16-_core_storage-.xml), not by test class,
    # so glob broadly and pick out our class's <testsuite> element.
    fresh = [
        xml
        for xml in results_root.glob("**/TEST-*.xml")
        if xml.stat().st_mtime >= started
    ]
    if not fresh:
        raise SystemExit(
            f"no fresh result XML under {results_root} "
            "(the connected run must produce one before the run may pass)"
        )
    spec = importlib.util.spec_from_file_location("android_summary", repo / "scripts/summarize-android-tests.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    result = helper.summarize(fresh)
    if result["status"] != "PASS" or any(case["class"] != CLASS for case in result["cases"]):
        raise SystemExit(f"unexpected storage connected result: {result['status']}")
    print(f"storage connected OK: {CLASS} tests={result['tests']} failures=0 errors=0 skipped=0 (serial={serial})")


if __name__ == "__main__":
    main()

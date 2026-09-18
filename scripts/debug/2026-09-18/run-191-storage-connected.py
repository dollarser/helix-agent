#!/usr/bin/env python3
"""HXA-191 follow-up on the owned serial: the storage connected query test.

Invoked by scripts/debug/2026-09-09/run-owned-emulator.py as
`python3 <this> <serial> <output-dir>` after the app instrumentation passes,
before the emulator is torn down. Builds and runs the Room-level search query
test on the same device, then fails the run unless a fresh result XML shows
4/4 passing for com.helix.core.storage.SessionSearchQueryDeviceTest.
"""
import os
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

CLASS = "com.helix.core.storage.SessionSearchQueryDeviceTest"
EXPECTED_TESTS = 4


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
    total = failures = errors = 0
    found = False
    for xml in fresh:
        for suite in ET.parse(xml).getroot().iter("testsuite"):
            if suite.get("name") == CLASS:
                found = True
                total += int(suite.get("tests", "0"))
                failures += int(suite.get("failures", "0"))
                errors += int(suite.get("errors", "0"))
    if not found:
        raise SystemExit(
            f"no testsuite for {CLASS} in the fresh result XMLs "
            f"({', '.join(str(xml) for xml in fresh)})"
        )
    if (total, failures, errors) != (EXPECTED_TESTS, 0, 0):
        raise SystemExit(
            f"unexpected storage connected result: tests={total} failures={failures} "
            f"errors={errors} (want {EXPECTED_TESTS}/0/0)"
        )
    print(
        f"storage connected OK: {CLASS} tests={total} failures=0 errors=0 "
        f"(serial={serial}, xml={', '.join(str(xml) for xml in fresh)})"
    )


if __name__ == "__main__":
    main()

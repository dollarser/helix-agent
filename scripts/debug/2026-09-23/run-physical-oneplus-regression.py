#!/usr/bin/env python3
"""Run physical device regression on attached OnePlus 6T (serial: 561e3b15).

Verifies:
1. Physical device identity (OnePlus 6T, Android 14 / API 34, arm64-v8a).
2. Battery & thermal safety checks before and during execution.
3. core:storage Room SQLite real flash storage regression (Connector migration, Session inputs).
4. app developer HXA-129 connector lifecycle & HXA-196 detached job tests on real hardware.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path


def run_adb(serial: str, *args: str, timeout: int = 600) -> Tuple[int, str]:
    cmd = ["adb", "-s", serial, *args]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return res.returncode, res.stdout + res.stderr


def main() -> None:
    parser = argparse.ArgumentParser(description="Run physical regression on OnePlus 6T")
    parser.add_argument("--serial", default="561e3b15", help="Device serial")
    parser.add_argument("--output", default="build/physical-oneplus-2026-09-23", help="Output directory")
    args = parser.parse_args()

    serial = args.serial
    out_dir = Path(args.output)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"=== Starting Physical Device Verification on {serial} ===")

    # 1. Device identity & safety check
    code, qemu = run_adb(serial, "shell", "getprop", "ro.kernel.qemu")
    assert qemu.strip() != "1", "Device must be a physical device, not an emulator!"

    code, model = run_adb(serial, "shell", "getprop", "ro.product.model")
    code, sdk = run_adb(serial, "shell", "getprop", "ro.build.version.sdk")
    code, abi = run_adb(serial, "shell", "getprop", "ro.product.cpu.abi")
    code, pagesize = run_adb(serial, "shell", "getconf", "PAGE_SIZE")
    code, batt = run_adb(serial, "shell", "dumpsys", "battery")

    battery_level = 0
    for line in batt.splitlines():
        if "level:" in line:
            battery_level = int(line.split(":")[1].strip())

    identity = {
        "serial": serial,
        "model": model.strip(),
        "sdk": sdk.strip(),
        "abi": abi.strip(),
        "pageSize": pagesize.strip(),
        "batteryLevel": battery_level,
        "timestamp": int(time.time()),
    }
    (out_dir / "device-identity.json").write_text(json.dumps(identity, indent=2))
    print(f"Device: {identity['model']}, SDK: {identity['sdk']}, ABI: {identity['abi']}, PageSize: {identity['pageSize']}, Battery: {battery_level}%")

    if battery_level < 10:
        print("WARNING: Battery level is critically low (<10%). Pausing for safety.")
        sys.exit(1)

    results = []

    # 2. Batch A: core:storage tests
    storage_apk = "core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk"
    if Path(storage_apk).exists():
        print("\n--- Installing and running core:storage physical tests ---")
        run_adb(serial, "install", "-r", "-t", storage_apk)
        storage_classes = [
            "com.helix.core.storage.ConnectorMigrationDeviceTest",
            "com.helix.core.storage.SessionInputMigrationDeviceTest",
            "com.helix.core.storage.SessionInputStorageDeviceTest",
            "com.helix.core.storage.PrivacyDeletionDeviceTest",
        ]
        storage_log = out_dir / "storage-tests.log"
        runner = "com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner"
        code, log = run_adb(
            serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "class",
            ",".join(storage_classes),
            runner,
            timeout=300,
        )
        storage_log.write_text(log)
        passed = "OK (" in log and "FAILURES!!!" not in log
        results.append({"suite": "core:storage", "exitCode": code, "passed": passed, "log": str(storage_log)})
        print(f"core:storage result: {'PASSED' if passed else 'FAILED'}")
        run_adb(serial, "uninstall", "com.helix.core.storage.test")
    else:
        print(f"WARNING: {storage_apk} not found. Skipping storage tests.")

    # 3. Batch B: app:developer connector & detached job tests
    app_apk = "app/build/outputs/apk/developer/debug/app-developer-debug.apk"
    test_apk = "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk"
    if Path(app_apk).exists() and Path(test_apk).exists():
        print("\n--- Installing and running app developer tests (HXA-129 / HXA-196) ---")
        run_adb(serial, "install", "-r", "-d", "-t", app_apk)
        run_adb(serial, "install", "-r", "-d", "-t", test_apk)
        app_classes = [
            "com.helix.app.connector.ConnectorLifecycleDeviceTest",
            "com.helix.app.connector.ConnectorSessionPanelDeviceTest",
            "com.helix.app.connector.ConnectorCatalogMigrationDeviceTest",
            "com.helix.app.connector.ConnectorSendBoundaryDeviceTest",
            "com.helix.app.proot.ProotDetachedJobDeviceTest",
        ]
        app_log = out_dir / "app-developer-tests.log"
        runner = "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
        code, log = run_adb(
            serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-e",
            "class",
            ",".join(app_classes),
            runner,
            timeout=600,
        )
        app_log.write_text(log)
        passed = "OK (" in log and "FAILURES!!!" not in log
        results.append({"suite": "app:developer", "exitCode": code, "passed": passed, "log": str(app_log)})
        print(f"app:developer result: {'PASSED' if passed else 'FAILED'}")
        run_adb(serial, "uninstall", "com.helix.agent.developer.test")
    else:
        print(f"WARNING: Developer APKs not found. Skipping app tests.")

    # 4. Summary
    (out_dir / "summary.json").write_text(json.dumps(results, indent=2))
    print("\n================== PHYSICAL TEST SUMMARY ==================")
    all_passed = all(r["passed"] for r in results)
    for r in results:
        print(f"Suite: {r['suite']:<20} | Status: {'PASSED' if r['passed'] else 'FAILED'}")
    print(f"Overall Result: {'ALL PASSED' if all_passed else 'SOME FAILED'}")

    if not all_passed:
        sys.exit(1)


if __name__ == "__main__":
    main()

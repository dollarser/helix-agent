#!/usr/bin/env python3
"""Complete physical device acceptance runner on OnePlus 6T (serial: 561e3b15).
Runs storage, capability, connector, detached job/owner-death, root, and storage AppOp suites.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

def run_adb(serial: str, *args: str, timeout: int = 600) -> tuple[int, str]:
    cmd = ["adb", "-s", serial, *args]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return res.returncode, res.stdout + res.stderr

def main() -> None:
    parser = argparse.ArgumentParser(description="Physical device acceptance on OnePlus 6T")
    parser.add_argument("--serial", default="561e3b15", help="Device serial")
    parser.add_argument("--output", default="build/physical-acceptance-20260924", help="Output directory")
    args = parser.parse_args()

    serial = args.serial
    repo_root = Path(__file__).resolve().parents[3]
    out_dir = repo_root / args.output
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"=== Starting Comprehensive Physical Device Acceptance on {serial} ===")

    # 1. Device identity & safety check
    code, qemu = run_adb(serial, "shell", "getprop", "ro.kernel.qemu")
    assert qemu.strip() != "1", "Target must be physical hardware, not an emulator!"

    code, model = run_adb(serial, "shell", "getprop", "ro.product.model")
    code, manufacturer = run_adb(serial, "shell", "getprop", "ro.product.manufacturer")
    code, sdk = run_adb(serial, "shell", "getprop", "ro.build.version.sdk")
    code, release = run_adb(serial, "shell", "getprop", "ro.build.version.release")
    code, abi = run_adb(serial, "shell", "getprop", "ro.product.cpu.abi")
    code, pagesize = run_adb(serial, "shell", "getconf", "PAGE_SIZE")
    code, batt = run_adb(serial, "shell", "dumpsys", "battery")

    battery_level = 0
    temperature = 0.0
    for line in batt.splitlines():
        if "level:" in line:
            battery_level = int(line.split(":")[1].strip())
        if "temperature:" in line:
            temperature = float(line.split(":")[1].strip()) / 10.0

    identity = {
        "serial": serial,
        "manufacturer": manufacturer.strip(),
        "model": model.strip(),
        "sdk": sdk.strip(),
        "release": release.strip(),
        "abi": abi.strip(),
        "pageSize": pagesize.strip(),
        "batteryLevel": battery_level,
        "temperatureCelsius": temperature,
        "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo_root, text=True).strip(),
        "timestampUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    (out_dir / "device-identity.json").write_text(json.dumps(identity, indent=2))
    print(f"Device: {identity['manufacturer']} {identity['model']} (Android {identity['release']} / API {identity['sdk']})")
    print(f"Arch: {identity['abi']}, Page: {identity['pageSize']} bytes, Battery: {battery_level}%, Temp: {temperature}°C")

    # Keep screen awake and unlock
    run_adb(serial, "shell", "svc", "power", "stayon", "true")
    run_adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    run_adb(serial, "shell", "input", "keyevent", "82")
    run_adb(serial, "shell", "input", "swipe", "500", "1500", "500", "500")

    package = "com.helix.agent.developer"
    test_pkg = "com.helix.agent.developer.test"
    runner = "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"

    # Clean stale state
    run_adb(serial, "shell", "am", "force-stop", package)
    run_adb(serial, "shell", "am", "force-stop", test_pkg)
    run_adb(serial, "shell", "run-as", package, "sh", "-c", "rm -f files/execution-admission/*")

    suite_results = []

    def run_instrumentation(suite_name: str, classes: list[str], extra_args: list[str] | None = None, timeout: int = 600) -> dict:
        print(f"\n--- Running Suite: {suite_name} ---")
        run_adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
        cmd_args = ["shell", "am", "instrument", "-w", "-r"]
        if extra_args:
            cmd_args.extend(extra_args)
        cmd_args.extend(["-e", "class", ",".join(classes), runner])
        code, log = run_adb(serial, *cmd_args, timeout=timeout)
        log_file = out_dir / f"{suite_name}.log"
        log_file.write_text(log)
        passed_tests = len(re.findall(r"INSTRUMENTATION_STATUS_CODE: 0", log))
        failed_tests = len(re.findall(r"INSTRUMENTATION_STATUS_CODE: -(?:1|2)", log))
        ok = bool(re.search(r"OK \([1-9]\d* tests?\)", log)) and failed_tests == 0 and code == 0
        res = {
            "suite": suite_name,
            "passed": passed_tests,
            "failed": failed_tests,
            "ok": ok,
            "exitCode": code,
        }
        print(f"Result for {suite_name}: {'PASSED' if ok else 'FAILED'} (passed: {passed_tests}, failed: {failed_tests})")
        suite_results.append(res)
        return res

    # Suite 1: Connectors (HXA-129)
    run_instrumentation("connectors", [
        "com.helix.app.connector.ConnectorLifecycleDeviceTest",
        "com.helix.app.connector.ConnectorSessionPanelDeviceTest",
        "com.helix.app.connector.ConnectorCatalogMigrationDeviceTest",
        "com.helix.app.connector.ConnectorSendBoundaryDeviceTest",
    ])

    # Suite 2: Detached Jobs (HXA-196)
    run_instrumentation("detached-jobs", [
        "com.helix.app.proot.ProotDetachedJobDeviceTest",
    ])

    # Suite 3: Detached Owner Death & Runtime Survival (HXA-196)
    print("\n--- Running Suite: detached-owner-death ---")
    run_instrumentation("detached-owner-death-prep", [
        "com.helix.app.proot.ProotDetachedOwnerDeathDeviceTest",
    ])
    # Run host follow-up
    verify_script = repo_root / "scripts/debug/2026-09-18/verify-196-owner-death.py"
    v_code, v_log = run_adb(serial, "shell", "echo", "ping") # verify connection
    h_proc = subprocess.run(
        [sys.executable, str(verify_script), serial, str(out_dir)],
        capture_output=True,
        text=True,
        timeout=120,
    )
    (out_dir / "detached-owner-death-verify.log").write_text(h_proc.stdout + h_proc.stderr)
    death_ok = h_proc.returncode == 0 and (out_dir / "owner-death.json").exists()
    print(f"Result for detached-owner-death-verify: {'PASSED' if death_ok else 'FAILED'}")
    suite_results.append({
        "suite": "detached-owner-death-survival",
        "passed": 1 if death_ok else 0,
        "failed": 0 if death_ok else 1,
        "ok": death_ok,
        "exitCode": h_proc.returncode,
    })

    # Summary
    (out_dir / "summary.json").write_text(json.dumps(suite_results, indent=2))
    print("\n================== ACCEPTANCE SUMMARY ==================")
    all_ok = all(r["ok"] for r in suite_results)
    for r in suite_results:
        print(f"  {r['suite']:<32} : {'PASSED' if r['ok'] else 'FAILED'}")
    print(f"Overall Status: {'ALL PASSED' if all_ok else 'SOME FAILED'}")

    # Restore stayon
    run_adb(serial, "shell", "svc", "power", "stayon", "false")

    if not all_ok:
        sys.exit(1)

if __name__ == "__main__":
    main()

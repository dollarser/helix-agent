#!/usr/bin/env python3
"""Run the isolated, test-signed R8 UI suite on one explicitly authorized device.

Never starts a device, updates an existing installation, calls an external model, or clears data.
The package is removed on exit; original raw results and APK identities are retained.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import shlex
import subprocess

from instrumentation_junit import require_passed, to_xml


def isolated_package(value):
    if not re.fullmatch(r"com\.helix\.validation\.[a-z][a-z0-9]*(?:\.developer)?", value):
        raise ValueError("An isolated com.helix.validation.<name> package is required")
    return value


def command(args, timeout=90):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=timeout).stdout.strip()


def installed_package(adb, package):
    return f"package:{package}" in command(adb + ["shell", "pm", "list", "packages", package]).splitlines()


def secure_setting(adb, key, value=None):
    args = ["settings", "get" if value is None else "put", "secure", key]
    if value is not None:
        args.append(value)
    return command(adb + ["shell", shlex.join(args)])


def restore_accessibility(adb, service, original):
    current = secure_setting(adb, "enabled_accessibility_services")
    remaining = [item for item in current.split(":") if item and item not in ("null", service)]
    original_empty = original["enabled_accessibility_services"] in ("", "null")
    value = ":".join(remaining) if remaining else original["enabled_accessibility_services"] if original_empty else "null"
    secure_setting(adb, "enabled_accessibility_services", value)
    if not remaining:
        secure_setting(adb, "accessibility_enabled", original["accessibility_enabled"])
    return service not in secure_setting(adb, "enabled_accessibility_services").split(":")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--application-id", required=True, type=isolated_package)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--suite", choices=("basic", "mobile-use", "mobile-use-execution"), default="basic")
    parser.add_argument("--boundary", choices=("cancel", "unknown", "stale"))
    parser.add_argument("--adb", default=shutil.which("adb"))
    parser.add_argument("--apkanalyzer", default=shutil.which("apkanalyzer"))
    args = parser.parse_args()
    if not args.adb or not args.apkanalyzer:
        parser.error("Put Android SDK adb and apkanalyzer on PATH or pass their paths")
    args.out_dir.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, "-s", args.serial]
    app = args.application_id
    if args.suite.startswith("mobile-use") and not app.endswith(".developer"):
        parser.error("Mobile Use currently requires the Developer target")
    if args.suite == "mobile-use-execution":
        if args.boundary is None or not re.fullmatch(r"emulator-\d+", args.serial):
            parser.error("Execution boundaries require --boundary and an explicitly authorized emulator serial")
    elif args.boundary is not None:
        parser.error("--boundary only applies to mobile-use-execution")
    test_package = "com.helix.validation.releasechecks.test"
    expected = [app, test_package]
    artifacts = []
    for path, package in zip((args.apk, args.test_apk), expected):
        if command([args.apkanalyzer, "manifest", "application-id", str(path)]) != package:
            raise ValueError("APK applicationId does not match the isolated target")
        if installed_package(adb, package):
            raise ValueError("Target is already installed; use a fresh isolated applicationId")
        artifacts.append({"package": package, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()})
    if command([args.apkanalyzer, "manifest", "debuggable", str(args.apk)]) != "false":
        raise ValueError("Target APK must be non-debuggable")
    metadata = {"serial": args.serial, "artifacts": artifacts, "status": "pending", "suite": args.suite}
    if args.boundary is not None:
        metadata["boundary"] = args.boundary
    report = args.out_dir / "result.json"
    report.write_text(json.dumps(metadata, indent=2) + "\n")
    installed = []
    accessibility = None
    service = app + "/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
    try:
        for path, package in zip((args.apk, args.test_apk), expected):
            installed.append(package)
            command(adb + ["install", "-t", str(path)])
        if args.suite.startswith("mobile-use"):
            accessibility = {key: secure_setting(adb, key) for key in
                             ("enabled_accessibility_services", "accessibility_enabled")}
            metadata["accessibility_before"] = accessibility
            report.write_text(json.dumps(metadata, indent=2) + "\n")
            original_services = [item for item in accessibility["enabled_accessibility_services"].split(":")
                                 if item and item != "null"]
            secure_setting(adb, "enabled_accessibility_services", ":".join(original_services + [service]))
            secure_setting(adb, "accessibility_enabled", "1")
        test_class = {"mobile-use": "MobileUseReleaseTest", "mobile-use-execution": "MobileUseExecutionReleaseTest",
                      "basic": "ReleaseSmokeTest"}[args.suite]
        extra = ["-e", "boundary", args.boundary] if args.boundary is not None else []
        try:
            result = subprocess.run(
                adb + ["shell", "am", "instrument", "-w", "-r", "-e", "targetPackage", app, "-e", "class",
                       "com.helix.smoke." + test_class] + extra +
                [test_package + "/androidx.test.runner.AndroidJUnitRunner"],
                capture_output=True, text=True, timeout=300,
            )
        except subprocess.TimeoutExpired as failure:
            def decoded(value):
                return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else (value or "")
            (args.out_dir / "instrumentation.log").write_text(decoded(failure.stdout) + decoded(failure.stderr))
            raise
        raw = result.stdout + result.stderr
        (args.out_dir / "instrumentation.log").write_text(raw)
        result.check_returncode()
        records = require_passed(raw)
        (args.out_dir / "junit.xml").write_text(to_xml(records))
        metadata.update(status="passed", tests=len(records))
    except (OSError, ValueError, subprocess.SubprocessError) as failure:
        metadata.update(status="failed", failure=type(failure).__name__)
        raise
    finally:
        cleanup = {}
        if accessibility is not None:
            try:
                cleanup["accessibility"] = restore_accessibility(adb, service, accessibility)
            except (OSError, subprocess.SubprocessError):
                cleanup["accessibility"] = False
        for package in reversed(installed):
            try:
                if installed_package(adb, package):
                    command(adb + ["uninstall", package])
                cleanup[package] = not installed_package(adb, package)
            except subprocess.SubprocessError:
                cleanup[package] = False
        metadata["cleanup"] = cleanup
        if not all(cleanup.values()):
            metadata["status"] = "failed"
            metadata.setdefault("failure", "CleanupIncomplete")
        report.write_text(json.dumps(metadata, indent=2) + "\n")
        if not all(cleanup.values()):
            raise RuntimeError("Isolated test package cleanup incomplete; see result.json")
    print(f"Release UI smoke passed: {metadata['tests']} methods; isolated packages removed")


if __name__ == "__main__":
    main()

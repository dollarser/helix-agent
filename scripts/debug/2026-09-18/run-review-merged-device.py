#!/usr/bin/env python3
"""Verify the merged repair and Runtime core on one fresh, exclusively owned emulator."""
from pathlib import Path
import runpy
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
variant, api, port, output = sys.argv[1:]
assert variant in ("consumer", "developer") and api in ("29", "36")
classes = [
    "com.helix.app.PermissionAtomicityDeviceTest",
    "com.helix.app.ProductionMigrationDeviceTest",
    "com.helix.app.SessionPermissionDeviceTest",
    "com.helix.app.SessionPermissionRecoveryDeviceTest",
    "com.helix.app.BrowserActivityLifecycleDeviceTest",
    "com.helix.app.ui.FilesImportExportUiTest#removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions",
]
suffix = ""
after = "run-review-storage-connected.py"
if variant == "developer":
    suffix = ".developer"
    classes += runpy.run_path(str(root / "scripts/verify-integrated-runtimes.py"))["CASES"]
    classes += [
        "com.helix.app.proot.ProotDetachedOwnerDeathDeviceTest",
        "com.helix.app.proot.ProotDetachedJobDeviceTest",
    ]
    after = "verify-196-owner-death.py"
subprocess.run([
    sys.executable, "scripts/debug/2026-09-09/run-owned-emulator.py",
    "--avd", f"HelixApkUpgrade_API{api}_20260918", "--port", port,
    "--memory-mb", "4096", "--cores", "4", "--output", output,
    "--apk", f"app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk",
    "--test-apk", f"app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk",
    "--runner", f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner",
    "--classes", ",".join(classes), "--timeout", "900",
    "--recovery-setup-class", "com.helix.app.SessionPermissionRecoveryDeviceTest",
    "--instrument-arg", "recoveryPhase=verify",
    "--after-script", "scripts/debug/2026-09-18/" + after,
], cwd=root, check=True)

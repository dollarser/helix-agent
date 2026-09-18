#!/usr/bin/env python3
"""HXA-193: run the 35-case integrated runtime suite on a new owned emulator."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CASES = [
    "com.helix.app.proot.IntegratedRuntimeDeviceTest",
    "com.helix.app.proot.LinuxRunToolE2eDeviceTest",
    "com.helix.app.provider.CodexSubscriptionProviderE2eDeviceTest",
    "com.helix.app.provider.CliRuntimeRunningRecoveryDeviceTest",
    "com.helix.app.proot.UpgradeRecoveryDeviceTest",
    "com.helix.app.proot.ProotRuntimeBindingE2eDeviceTest#aProcessDeathIsDeadObjectAndTheColdRebindRecovers",
    "com.helix.app.proot.ProotRuntimeBindingE2eDeviceTest#aNullOnBindIsAnImmediateBindRefusedNotATimeout",
    "com.helix.app.proot.ProotJobE2eDeviceTest",
    "com.helix.app.proot.IntegratedRuntimeUiDeviceTest",
]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--memory-mb", type=int, choices=(2048, 4096), default=2048)
    parser.add_argument("--cores", type=int, choices=(2, 4), default=2)
    parser.add_argument("--output", required=True, help="New output directory; never reuses existing evidence")
    args = parser.parse_args()
    subprocess.run([
        sys.executable, str(ROOT / "scripts/debug/2026-09-09/run-owned-emulator.py"),
        "--avd", args.avd, "--port", str(args.port), "--output", args.output,
        "--memory-mb", str(args.memory_mb), "--cores", str(args.cores),
        "--apk", str(ROOT / "app/build/outputs/apk/developer/debug/app-developer-debug.apk"),
        "--test-apk", str(ROOT / "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk"),
        "--runner", "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
        "--classes", ",".join(CASES), "--timeout", "600",
    ], check=True, cwd=ROOT)

#!/usr/bin/env python3
"""Run the preserved old-APK/new-APK journey under with-host-slot on two owned AVDs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--old-apk", type=Path, required=True)
parser.add_argument("--old-test-apk", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--first-port", type=int, default=5610)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
environment = dict(os.environ,
                   HELIX_UPGRADE_NEW_APK=str(root / "app/build/outputs/apk/developer/debug/app-developer-debug.apk"),
                   HELIX_UPGRADE_NEW_TEST_APK=str(root / "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk"))
for index, api in enumerate((29, 36)):
    target = args.output / f"api{api}"
    command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
               "--avd", f"Helix191_API{api}", "--port", str(args.first_port + 2 * index),
               "--memory-mb", "4096", "--cores", "4", "--apk", str(args.old_apk.resolve()),
               "--test-apk", str(args.old_test_apk.resolve()),
               "--runner", "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
               "--classes", "com.helix.app.proot.ApkReplacementUpgradeDeviceTest",
               "--instrument-arg", "upgradePhase=seed", "--after-script", "scripts/debug/2026-09-18/apk-upgrade-after.py",
               "--output", str(target), "--timeout", "600"]
    with (args.output / f"api{api}.log").open("w") as log:
        subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
    result = json.loads((target / "upgrade-result.json").read_text())
    print(f"API{api}: {result['result']}; no data clear; owned runner closed", flush=True)

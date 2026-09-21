#!/usr/bin/env python3
"""Run under with-host-slot after host gates, using two newly owned emulator processes."""
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
for api, port in ((29, 5584), (36, 5586)):
    subprocess.run([
        sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
        "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
        "--apk", "app/build/outputs/apk/developer/debug/app-developer-debug.apk",
        "--test-apk", "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk",
        "--runner", "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
        "--classes", "com.helix.app.proot.ProotMultiSessionDeviceTest,com.helix.app.proot.ProotTerminalSessionDeviceTest,com.helix.app.proot.TerminalHostJourneyDeviceTest",
        "--instrument-arg", "terminalHostPhase=prepare",
        "--after-script", "scripts/debug/2026-09-21/run-dual-terminal-main-death.py",
        "--output", f"build/{prefix}-api{api}", "--timeout", "600",
    ], cwd=root, check=True)

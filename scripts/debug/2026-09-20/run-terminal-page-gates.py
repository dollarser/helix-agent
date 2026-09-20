#!/usr/bin/env python3
"""Run under with-host-slot after host gates; each device run owns and closes its emulator."""
import importlib.util
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
ui_only = sys.argv[2:] == ["--ui-only"]
runtime_only = ui_only or sys.argv[2:] == ["--runtime-only"]
if sys.argv[2:] and not runtime_only:
    raise SystemExit("expected optional --runtime-only or --ui-only")
spec = importlib.util.spec_from_file_location("runtime_suite", root / "scripts/verify-integrated-runtimes.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
subprocess.run(["./gradlew", ":app:assembleDeveloperDebug", ":app:assembleConsumerDebug",
                ":app:assembleDeveloperDebugAndroidTest", ":app:assembleConsumerDebugAndroidTest"],
               cwd=root, check=True)
for api, port, locale in ((29, 5584, "zh"), (36, 5586, "en")):
    subprocess.run([
        sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
        "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
        "--apk", "app/build/outputs/apk/developer/debug/app-developer-debug.apk",
        "--test-apk", "app/build/outputs/apk/androidTest/developer/debug/app-developer-debug-androidTest.apk",
        "--runner", "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
        "--classes", ",".join(([] if ui_only else module.CASES) + ["com.helix.app.proot.ProotTerminalUiDeviceTest"]),
        "--instrument-arg", "terminalLocale=" + locale,
        "--after-script", "scripts/debug/2026-09-20/collect-terminal-ui.py",
        "--output", f"build/{prefix}-api{api}", "--timeout", "600",
    ], cwd=root, check=True)
if not runtime_only:
    subprocess.run(["bash", "scripts/debug/2026-09-20/run-final-theme-matrix.sh", prefix + "-theme", "5588"],
                   cwd=root, check=True)
subprocess.run([sys.executable, "scripts/verify-integrated-runtime-apks.py", "--build-type", "release"],
               cwd=root, check=True)

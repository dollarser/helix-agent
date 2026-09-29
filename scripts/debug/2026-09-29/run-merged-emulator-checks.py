#!/usr/bin/env python3
"""Owner-authorized bounded API36 regression; never selects a connected phone."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
classes = [
    "ui.HierarchicalNavigationDeviceTest", "ui.ProviderContextRecoveryDeviceTest",
    "ui.ProviderSettingsFormDeviceTest", "ui.ProviderOptionalKeyDeviceTest",
    "chat.ToolVisionFlowDeviceTest", "chat.ConversationDraftRecoveryDeviceTest",
    "chat.ChatSubmissionReceiptDeviceTest", "chat.ChatServiceAttachmentRetryDeviceTest",
]
run_name = sys.argv[1]
failures = []
for index, channel in enumerate(("developer", "consumer")):
    package = "com.helix.agent" + (".developer" if channel == "developer" else "")
    env = os.environ.copy()
    env.update(HXA214_PACKAGE=package, HELIX_MERGED_SGLANG=str(channel == "developer").lower())
    for phase in (("recovery",) if os.environ.get("HELIX_RECOVERY_ONLY") == "true" else ("suite", "recovery")):
        output = ROOT / "build" / f"{run_name}-{channel}-{phase}"
        command = [sys.executable, "scripts/run-owned-emulator.py",
            "--avd", "Helix_HXA229_Closeout_API36", "--port", str(5560 + index * 4 + (phase == "recovery") * 2),
            "--memory-mb", "4096", "--apk", f"app/build/outputs/apk/{channel}/debug/app-{channel}-debug.apk",
            "--test-apk", f"app/build/outputs/apk/androidTest/{channel}/debug/app-{channel}-debug-androidTest.apk",
            "--runner", package + ".test/com.helix.app.HelixAndroidJUnitRunner",
            "--output", str(output), "--timeout", "1500", "--raw-results", "--clear-app-data",
            "--classes", ",".join("com.helix.app." + name for name in classes) if phase == "suite" else
            "com.helix.app.chat.ComposerProcessRecoveryDeviceTest#seedComposerRecovery"]
        if phase == "recovery":
            command += ["--after-script", "scripts/debug/2026-09-29/merged-emulator-followup.py"]
        print(f"Starting {channel} {phase}", flush=True)
        with output.with_suffix(".log").open("w") as log:
            result = subprocess.run(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        print(f"Finished {channel} {phase}: {result.returncode}", flush=True)
        if result.returncode:
            failures.append(f"{channel}/{phase}")
if failures:
    raise SystemExit("Failed: " + ", ".join(failures))

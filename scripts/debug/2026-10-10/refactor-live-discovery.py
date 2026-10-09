#!/usr/bin/env python3
"""Owner-authorized six-run single-variable discovery comparison; synthetic fixtures only."""
import os
from pathlib import Path
import subprocess
import sys
import time

adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
serial, provider_id, model = sys.argv[1:]
output = Path("build/refactor-closeout-20261010/discovery")
output.mkdir(parents=True, exist_ok=False)
runner = "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
for index, explicit in enumerate((False, True, True, False, False, True), 1):
    started = time.monotonic()
    result = subprocess.run(
        [adb, "-s", serial, "shell", "am", "instrument", "-w",
         "-e", "class", "com.helix.app.proot.LiveJobAwaitDeviceTest#realModelWaitsAndCollectsOneOriginalExecution",
         "-e", "liveJob", "true", "-e", "liveProviderId", provider_id,
         "-e", "liveModel", model, "-e", "liveDiscoveryExplicit", str(explicit).lower(), runner],
        capture_output=True, text=True, timeout=330, check=True,
    )
    label = f"{index}-{'candidate' if explicit else 'baseline'}"
    (output / f"{label}.txt").write_text(result.stdout + result.stderr)
    (output / f"{label}.seconds").write_text(str(time.monotonic() - started))
    print(label, result.stdout, flush=True)

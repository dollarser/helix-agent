#!/usr/bin/env python3
"""Uninstall both app packages on the owned serial before the runner tears it down."""
import os
import subprocess
import sys

serial, output = sys.argv[1], sys.argv[2]
sdk = os.environ["ANDROID_HOME"]
adb = sdk + "/platform-tools/adb"
lines = []
for pkg in (os.environ["CLEAN_PKG_MAIN"], os.environ["CLEAN_PKG_TEST"]):
    result = subprocess.run([adb, "-s", serial, "shell", "pm", "uninstall", pkg],
                            text=True, capture_output=True, timeout=60)
    lines.append(f"{pkg}: {result.stdout.strip()} {result.stderr.strip()}")
with open(os.path.join(output, "clean-packages.txt"), "w") as log:
    log.write("\n".join(lines) + "\n")
print("\n".join(lines))

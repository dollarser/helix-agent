#!/usr/bin/env python3
"""Archive only known synthetic terminal probe screenshots from the owned test device."""
import os
from pathlib import Path
import subprocess
import sys
import tarfile

serial, output = sys.argv[1:]
root = Path(output)
archive = root / "terminal-ui.tar"
with archive.open("wb") as target:
    subprocess.run([str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial,
                    "exec-out", "run-as", "com.helix.spike.termlib", "tar", "-cf", "-",
                    "-C", "cache", "terminal-ui"], stdout=target, check=True, timeout=30)
with tarfile.open(archive) as source:
    source.extractall(root / "screenshots", filter="data")
print("Archived synthetic UI screenshots")

#!/usr/bin/env python3
"""Collect synthetic layout screenshots before the owned runner closes its emulator."""
import os
from pathlib import Path
import re
import subprocess
import sys

serial, destination = sys.argv[1:]
output = Path(destination)
if not re.fullmatch(r"(?:consumer|developer)-api(?:29|36)", output.name):
    raise SystemExit("Unexpected owned batch identity")
package = "com.helix.agent" + (".developer" if output.name.startswith("developer") else "")
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
prefix = [adb, "-s", serial, "exec-out", "run-as", package]
names = subprocess.check_output(prefix + ["ls", "-1", "cache/hxa147-layout"], text=True).splitlines()
(output / "screenshot-names.txt").write_text("\n".join(names) + "\n")
screenshots = output / "screenshots"
screenshots.mkdir(exist_ok=False)
for name in names:
    if not re.fullmatch(r"[a-z0-9-]+\.png", name):
        raise SystemExit(f"Unexpected screenshot name: {name!r}")
    image = subprocess.check_output(prefix + ["cat", f"cache/hxa147-layout/{name}"])
    if not image.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit("Invalid PNG capture")
    (screenshots / name).write_bytes(image)
if not names:
    raise SystemExit("No layout screenshots captured")
print(f"Collected {len(names)} synthetic screenshots")

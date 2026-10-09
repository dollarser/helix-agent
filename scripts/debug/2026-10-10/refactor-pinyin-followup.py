#!/usr/bin/env python3
"""Configure Chinese Pinyin on the exclusive API29 clone, then run bounded checks."""
import os
from pathlib import Path
import subprocess
import sys

serial, directory = sys.argv[1:]
root = Path(directory)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")

def device(*args):
    return subprocess.run([adb, "-s", serial, *args], capture_output=True, text=True,
                          check=True, timeout=40).stdout

if device("shell", "getprop", "ro.build.version.sdk").strip() == "29":
    available = device("shell", "ime", "list", "-a", "-s")
    (root / "ime-all-inventory.txt").write_text(available)
    pinyin = [entry for entry in available.splitlines() if ".pinyin/" in entry]
    if len(pinyin) != 1:
        raise RuntimeError("Expected one existing Google Pinyin IME on the API29 clone")
    print(device("shell", "ime", "enable", pinyin[0]), flush=True)
    print(device("shell", "ime", "set", pinyin[0]), flush=True)

subprocess.run(["python3", "scripts/debug/2026-10-10/refactor-device-followup.py", serial, directory],
               check=True, timeout=240)

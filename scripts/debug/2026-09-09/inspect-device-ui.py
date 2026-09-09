#!/usr/bin/env python3
"""Capture UI from an explicit phone or owned emulator into ignored build output."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import uuid
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--owner", help="Required owner.json for emulator inspection")
parser.add_argument("--output", required=True)
args = parser.parse_args()
output = Path(args.output).resolve()
project = Path(__file__).resolve().parents[3]
if not output.is_relative_to(project / "build"):
    raise ValueError("UI may contain private data; output must be in the ignored build directory")
if args.serial.startswith("emulator-"):
    if not args.owner:
        raise ValueError("Emulator inspection requires our live owner record")
    owner = json.loads(Path(args.owner).read_text())
    if owner["serial"] != args.serial or Path(args.owner).with_name("closed.json").exists():
        raise ValueError("Owner record is closed or serial differs")
    os.kill(owner["pid"], 0)
output.mkdir(parents=True, exist_ok=False)
adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", args.serial]
remote = f"/sdcard/helix-debug-{uuid.uuid4().hex}.xml"

def device(*command):
    return subprocess.check_output([*adb, *command], timeout=30)

try:
    report = device("shell", "uiautomator", "dump", remote).decode()
    if "UI hierchary dumped to:" not in report:
        raise RuntimeError("UI dump failed; refusing stale XML")
    xml = device("shell", "cat", remote)
    nodes = ET.fromstring(xml)
    (output / "ui.xml").write_bytes(xml)
    (output / "screen.png").write_bytes(device("exec-out", "screencap", "-p"))
    for node in nodes.iter("node"):
        if node.get("text") or node.get("content-desc"):
            print(node.get("text"), node.get("content-desc"), node.get("bounds"))
finally:
    device("shell", "rm", "-f", remote)

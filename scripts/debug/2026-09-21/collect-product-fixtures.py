#!/usr/bin/env python3
"""Preserve named synthetic UI evidence before our owned emulator closes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

serial, directory = sys.argv[1:]
output = Path(directory)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial and not (output / "closed.json").exists()
package = os.environ["HELIX_FIXTURE_PACKAGE"]
assert package in ("com.helix.agent", "com.helix.agent.developer")
group = os.environ["HELIX_FIXTURE_GROUP"]
if group in ("theme-light", "theme-dark"):
    mode = "night" if group == "theme-dark" else "day"
    paths = [f"cache/hxa191-theme/compose-surface-{mode}.png"]
elif group == "export":
    paths = [f"files/session-export-{name}.png" for name in ("dialog", "picker", "completed")]
    paths.append("files/session-export-picker.jsonl")
else:
    raise ValueError("Unsupported fixture group")
adb = [str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb"), "-s", serial]
index = []
for source in paths:
    os.kill(owner["pid"], 0)
    assert not (output / "closed.json").exists()
    target = output / Path(source).name
    with target.open("wb") as stream:
        subprocess.run(adb + ["exec-out", "run-as", package, "cat", source],
                       stdout=stream, timeout=30, check=True)
    assert 0 < target.stat().st_size <= 10 * 1024 * 1024, "Invalid fixture artifact size"
    content = target.read_bytes()
    if target.suffix == ".png":
        assert content.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot"
    else:
        records = [json.loads(line) for line in content.decode().splitlines()]
        assert records and records[-1].get("type") == "complete", "Export did not complete"
    index.append({"file": target.name, "sha256": hashlib.sha256(content).hexdigest(),
                  "bytes": len(content), "source": source})
(output / "fixture-artifacts.json").write_text(json.dumps(index, indent=2) + "\n")

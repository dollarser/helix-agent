"""Bounded synthetic owner-death checks on the emulator owned by the calling runner."""
import json
import os
from pathlib import Path
import subprocess
import sys

serial, directory = sys.argv[1:]
output = Path(directory)
adb = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
cases = [
    ("javascript-execution", "scripts/run-javascript-process-kill.py", []),
    ("proot-owner", "scripts/run-proot-owner-process-kill.py", []),
    ("cli-owner", "scripts/run-cli-owner-process-kill.py", ["--provider", "CODEX"]),
    ("cli-result-fetched", "scripts/run-cli-result-owner-kill.py", ["--boundary", "fetched"]),
    ("cli-result-persisted", "scripts/run-cli-result-owner-kill.py", ["--boundary", "persisted"]),
    ("cli-result-acknowledged", "scripts/run-cli-result-owner-kill.py", ["--boundary", "acknowledged"]),
    ("cli-result-local", "scripts/run-cli-result-owner-kill.py", ["--boundary", "acknowledged", "--local-only"]),
]
selection = os.environ.get("HELIX_OWNER_CASES")
if selection:
    requested = selection.split(",")
    assert len(requested) == len(set(requested)) and set(requested) <= {case[0] for case in cases}
    cases = [case for case in cases if case[0] in requested]
results = []
for name, script, extra in cases:
    with (output / f"{name}-host.log").open("w") as log:
        result = subprocess.run(
            [sys.executable, script, "--serial", serial, "--adb", adb,
             "--output", str(output / name), *extra],
            stdout=log, stderr=subprocess.STDOUT, timeout=240,
        )
    results.append({"case": name, "exitCode": result.returncode})
    (output / "owner-matrix.json").write_text(json.dumps(results, indent=2) + "\n")
    if result.returncode:
        raise SystemExit(result.returncode)

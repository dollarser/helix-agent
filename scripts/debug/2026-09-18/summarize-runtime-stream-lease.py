#!/usr/bin/env python3
"""Verify the exact four owned runs before reporting the Runtime stream/lease slice."""
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
prefix = sys.argv[1]
rows = []
for api in (29, 36):
    for suite, expected in (("runtime", 35), ("stream-lease", 13)):
        directory = root / "build" / f"{prefix}-{suite}-api{api}"
        text = (directory / "instrumentation.txt").read_text()
        counts = re.findall(r"OK \((\d+) tests?\)", text)
        if counts != [str(expected)] or "FAILURES!!!" in text:
            raise SystemExit(f"Unexpected instrumentation result: {directory.name}")
        closed = json.loads((directory / "closed.json").read_text())
        if closed["exit"] != 0:
            raise SystemExit(f"Owned emulator did not close cleanly: {directory.name}")
        row = {"api": api, "suite": suite, "tests": expected, "closed": closed}
        row["artifacts"] = json.loads((directory / "artifacts.json").read_text())
        if suite == "stream-lease":
            proof = json.loads((directory / "owner-death.json").read_text())
            if proof["record"]["state"] != "SUCCEEDED":
                raise SystemExit(f"Owner-death result is not successful: {directory.name}")
            row["ownerDeath"] = proof
        rows.append(row)
result = {"instrumentation": sum(row["tests"] for row in rows), "independentOwnerDeathChecks": 2, "runs": rows}
destination = root / "build" / f"{prefix}-summary.json"
destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(destination)
print("96 instrumentation cases (including 2 owner-probe preparations); 2 independent owner-death checks")

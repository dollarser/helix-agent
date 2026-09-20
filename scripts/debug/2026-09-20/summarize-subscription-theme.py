#!/usr/bin/env python3
"""Verify both system modes and nonzero tests on two freshly owned developer emulators."""
import json
from pathlib import Path
import re
import sys

prefix = sys.argv[1]
expected = int(sys.argv[2])
assert expected > 0
rows = []
for api in (29, 36):
    root = Path("build") / f"{prefix}-api{api}"
    artifacts = json.loads((root / "artifacts.json").read_text())
    closed = json.loads((root / "closed.json").read_text())
    assert closed["exit"] == 0, closed
    if rows:
        assert artifacts == rows[0]["artifacts"], "APK identities changed"
    for mode, night in (("light", "no"), ("dark", "yes")):
        result = (root / f"{mode}-instrument.txt").read_text()
        assert re.findall(r"OK \((\d+) tests?\)", result) == [str(expected)], result
        assert not any(s in result for s in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
        assert (root / f"{mode}-uimode.txt").read_text().strip() == f"Night mode: {night}"
    rows.append(dict(api=api, tests=expected * 2, artifacts=artifacts, closed=closed))
Path(f"build/{prefix}-summary.json").write_text(json.dumps(rows, indent=2) + "\n")
print(f"{expected * 4} theme cases passed; actual day/night modes, owned teardown and APK identities verified")

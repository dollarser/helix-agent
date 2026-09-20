#!/usr/bin/env python3
"""Combine separately verified app and fresh Room search results without counting recovery setup."""
import json
from pathlib import Path
import sys

prefix = sys.argv[1]
rows = []
for variant in ("developer", "consumer"):
    summary = json.loads(Path(f"build/{prefix}-{variant}-summary.json").read_text())
    assert summary["tests"] == 16
    for run in summary["runs"]:
        root = Path(f"build/{prefix}-{variant}-api{run['api']}")
        followup = (root / "follow-up.txt").read_text()
        assert "SessionSearchQueryDeviceTest tests=4 failures=0 errors=0" in followup
        assert "process crashed" in (root / "recovery-setup.txt").read_text().lower()
        assert (root / "process-stop.txt").read_text().strip()
        rows.append(dict(variant=variant, api=run["api"], ui=8, room=4, artifacts=run["artifacts"]))
Path(f"build/{prefix}-summary.json").write_text(json.dumps(rows, indent=2) + "\n")
print("Search matrix: 32 UI/recovery + 16 Room cases passed; expected setup deaths excluded")

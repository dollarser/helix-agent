#!/usr/bin/env python3
"""Separate preparation tests from actual post-death collection verification."""
import json
from pathlib import Path
import sys

prefix = sys.argv[1]
summary = json.loads(Path(f"build/{prefix}-summary.json").read_text())
journeys = []
for api in (29, 36):
    directory = Path(f"build/{prefix}-api{api}")
    facts = json.loads((directory / "host-job-result.json").read_text())
    log = (directory / "host-job-verify.txt").read_text()
    assert facts["mainPid"] != facts["newMainPid"]
    assert "RUNNING" in facts["states"] and facts["states"][-1] == "SUCCEEDED"
    assert facts["verifyTests"] == 1 and facts["modelCalls"] == 2
    assert "OK (1 test)" in log and "FAILURES" not in log
    journeys.append(dict(api=api, **facts))
Path(f"build/{prefix}-journeys.json").write_text(json.dumps({
    "prepareTests": summary["tests"], "verificationTests": len(journeys), "journeys": journeys,
}, indent=2))
print(f"{len(journeys)} ordinary-process death journeys verified; {summary['tests']} preparation tests separate")

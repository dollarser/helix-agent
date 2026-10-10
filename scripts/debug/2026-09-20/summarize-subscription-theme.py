#!/usr/bin/env python3
"""Verify both system modes and nonzero tests on two freshly owned developer emulators."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "scripts"))
import importlib.util
spec = importlib.util.spec_from_file_location("owned_summary", Path(sys.path[0]) / "summarize-owned-runs.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
prefix = sys.argv[1]
directories = [Path("build") / f"{prefix}-api{api}" for api in (29, 36)]
result = helper.summarize(directories, ("light-instrument.txt", "dark-instrument.txt"))
for directory in directories:
    for mode, night in (("light", "no"), ("dark", "yes")):
        assert (directory / f"{mode}-uimode.txt").read_text().strip() == f"Night mode: {night}"
with Path(f"build/{prefix}-summary.json").open("x") as target:
    json.dump(result, target, indent=2)
print(f"{result['tests']} actual theme cases passed; day/night, owner and APK identity verified")

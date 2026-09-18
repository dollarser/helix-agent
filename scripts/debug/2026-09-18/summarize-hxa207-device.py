#!/usr/bin/env python3
"""Validate all 8 HXA-207 device evidence dirs and write summary.json (real counts/skips/exit).

matrix run : `OK (12 tests)` in instrumentation.txt and the logcat `run finished:` line reports
             `12 tests, 0 failed,`. Every matrix skip is an `assumption failed` line in the same
             pid: developer builds skip only the 2 restart methods (no extensionJourneyPhase
             argument); consumer builds additionally skip the 5 developer-lane tests (their
             assumeTrue on AdvancedProfileAvailability.ADVANCED_AVAILABLE), i.e. 7 skips.
restart run: `OK (1 tests)` for the seed (instrumentation.txt) AND `OK (1 tests)` for the recover
             (follow-up.txt, the after-script's force-stop + re-instrument in the new process).
"""
import hashlib
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[3]
base = root / "build/hxa207-device"
QUADRANTS = [(flavor, api) for flavor in ("consumer", "developer") for api in (29, 36)]
MATRIX_TOTAL = 12
# Developer: the 2 restart methods skip. Consumer: those plus the 5 developer-lane MCP tests.
MATRIX_SKIPS = {"developer": 2, "consumer": 7}
FAIL_MARKERS = ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")


def artifacts(flavor):
    out = {}
    for kind, path in {
        "app": f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
        "test": f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
    }.items():
        with (root / path).open("rb") as handle:
            out[kind] = hashlib.file_digest(handle, "sha256").hexdigest()
    return out


def instrument(output_dir, expected_total):
    text = (output_dir / "instrumentation.txt").read_text()
    assert json.loads((output_dir / "closed.json").read_text())["exit"] == 0, output_dir
    match = re.search(rf"^OK \({expected_total} tests?\)", text, re.M)
    assert match, (output_dir, "expected OK (" + str(expected_total) + " tests)")
    assert not any(marker in text for marker in FAIL_MARKERS), output_dir
    time_match = re.search(r"Time: ([\d.]+)", text)
    return float(time_match.group(1)) if time_match else None


records = []
for flavor, api in QUADRANTS:
    arts = artifacts(flavor)
    skips = MATRIX_SKIPS[flavor]
    # --- matrix: developer-lane tests (and the restart pair) skip via assumption ---
    matrix = base / f"{flavor}-{api}-matrix"
    assert json.loads((matrix / "artifacts.json").read_text()) == arts, matrix
    seconds = instrument(matrix, MATRIX_TOTAL)
    logs = (matrix / "test-logcat.txt").read_text().splitlines()
    finished = [line for line in logs if "run finished:" in line][-1]
    assert f"{MATRIX_TOTAL} tests, 0 failed," in finished, (matrix, finished)
    pid = finished.split()[2]
    assumptions = [line for line in logs
                   if len(line.split()) > 2 and line.split()[2] == pid and "assumption failed" in line]
    assert len(assumptions) == skips, (matrix, assumptions)
    records.append({"flavor": flavor, "api": api, "phase": "matrix",
                    "total": MATRIX_TOTAL, "passed": MATRIX_TOTAL - skips, "skipped": skips,
                    "seconds": seconds})

    # --- restart: seed (main run) + recover (after-script in the new process) ---
    restart = base / f"{flavor}-{api}-restart"
    assert json.loads((restart / "artifacts.json").read_text()) == arts, restart
    instrument(restart, 1)  # seed
    followup = (restart / "follow-up.txt").read_text()
    assert re.search(r"^OK \(1 tests?\)", followup, re.M), (restart, "recover did not report OK (1 tests)")
    assert not any(marker in followup for marker in FAIL_MARKERS), restart
    records.append({"flavor": flavor, "api": api, "phase": "restart",
                    "total": 2, "passed": 2, "skipped": 0, "seconds": None})

summary = {
    "testClass": "com.helix.app.ExtensionJourneyDeviceTest",
    "quadrants": len(QUADRANTS),
    "matrixPassed": sum(r["passed"] for r in records if r["phase"] == "matrix"),
    "restartPassed": sum(r["passed"] for r in records if r["phase"] == "restart"),
    "totalPassed": sum(r["passed"] for r in records),
    "skippedInMatrix": sum(r["skipped"] for r in records if r["phase"] == "matrix"),
    "failed": 0,
    "ownedRuns": 2 * len(QUADRANTS),
    "runs": records,
}
(base / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(f"HXA-207 device: {summary['matrixPassed']} matrix + {summary['restartPassed']} restart "
      f"passed, {summary['skippedInMatrix']} matrix skips (developer-lane assumptions), "
      f"0 failed; {summary['ownedRuns']} owned runs")

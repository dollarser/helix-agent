"""Summarize exact task outputs, never recursively include historical build archives."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

DIRECTORIES = (
    "runtime/quickjs/build/test-results/testDebugUnitTest",
    "app/build/test-results/testConsumerDebugUnitTest",
    "app/build/test-results/testDeveloperDebugUnitTest",
)
FOCUSED = {"com.helix.runtime.quickjs.JsNativeLifecycleTest", "com.helix.app.agent.ContextCapacityTest"}
FIELDS = ("tests", "failures", "errors", "skipped")
report = []
for directory in DIRECTORIES:
    paths = sorted(Path(directory).glob("TEST-*.xml"))
    if not paths:
        raise SystemExit(f"Missing test output: {directory}")
    totals = dict.fromkeys(FIELDS, 0)
    focused = []
    for path in paths:
        root = ET.parse(path).getroot()
        counts = {field: int(root.get(field, "0")) for field in FIELDS}
        for field in FIELDS:
            totals[field] += counts[field]
        if root.get("name") in FOCUSED:
            focused.append({"suite": root.get("name"), "timestamp": root.get("timestamp"), **counts})
    if not focused or totals["tests"] == 0 or totals["failures"] or totals["errors"]:
        raise SystemExit(f"Missing focused suite or failed tests: {directory}: {totals}")
    report.append({"directory": directory, **totals, "focused": focused})
output = Path("build/optimization-followup-2026-09-30/test-summary.json")
output.parent.mkdir(parents=True, exist_ok=True)
text = json.dumps(report, indent=2, ensure_ascii=False)
output.write_text(text + "\n", encoding="utf-8")
print(text)

"""Record the final HXA-229 host JUnit totals and APK identities."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
results = {}
for directory in (
    "core/model/build/test-results/test",
    "core/agent/build/test-results/test",
    "core/storage/build/test-results/testDebugUnitTest",
    "app/build/test-results/testConsumerDebugUnitTest",
    "app/build/test-results/testDeveloperDebugUnitTest",
):
    reports = list((root / directory).glob("TEST-*.xml"))
    assert reports, directory
    totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    results[directory] = totals
artifacts = {}
for pattern in ("app/build/outputs/apk/**/*.apk", "core/storage/build/outputs/apk/**/*.apk"):
    for apk in root.glob(pattern):
        if "debug" in str(apk).lower():
            artifacts[str(apk.relative_to(root))] = hashlib.sha256(apk.read_bytes()).hexdigest()
output = root / "build/hxa229-closeout/host-summary.json"
output.write_text(json.dumps({"junit": results, "sha256": artifacts}, indent=2) + "\n")
print(json.dumps(results, indent=2))

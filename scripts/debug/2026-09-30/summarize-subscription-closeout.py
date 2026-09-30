#!/usr/bin/env python3
"""Record exact current host reports and APK digests; never count archived reports."""
import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
label = sys.argv[1] if len(sys.argv) > 1 else "provider-chain-closeout"
assert label.replace("-", "").isalnum(), "Invalid evidence label"
reports = {
    "consumer": "app/build/test-results/testConsumerDebugUnitTest",
    "developer": "app/build/test-results/testDeveloperDebugUnitTest",
    "cli-app": "runtime/cli-app/build/test-results/testDebugUnitTest",
    "cli-client": "runtime/cli-client/build/test-results/testDebugUnitTest",
    "proot-app": "runtime/proot-app/build/test-results/testDebugUnitTest",
    "proot-client": "runtime/proot-client/build/test-results/testDebugUnitTest",
}
result = {"observed_at": datetime.now(timezone.utc).isoformat(),
          "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
          "reports": {}, "apks": {}}
for name, directory in reports.items():
    files = sorted((root / directory).glob("TEST-*.xml"))
    assert files, f"Missing reports: {directory}"
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    for file in files:
        suite = ET.parse(file).getroot()
        for key in totals:
            totals[key] += int(suite.get(key, "0"))
    result["reports"][name] = {"directory": directory, **totals}
for file in sorted((root / "app/build/outputs/apk").glob("**/*debug*.apk")):
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    result["apks"][str(file.relative_to(root))] = {"bytes": file.stat().st_size, "sha256": digest.hexdigest()}
out = root / f"build/{label}-2026-09-30/host-summary.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
print(json.dumps(result, ensure_ascii=False, indent=2))

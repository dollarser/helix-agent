"""Read HXA-176 local JUnit/artifact evidence; never operate a device."""

import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def main():
    report = {}
    for variant in ("Consumer", "Developer"):
        directory = Path(f"app/build/test-results/test{variant}DebugUnitTest")
        totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
        files = sorted(directory.glob("TEST-*.xml"))
        if not files:
            raise RuntimeError(f"No test XML: {directory}")
        for path in files:
            root = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(root.attrib.get(key, "0"))
        apk = Path(f"app/build/outputs/apk/{variant.lower()}/debug/app-{variant.lower()}-debug.apk")
        report[variant] = {**totals, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

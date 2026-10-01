#!/usr/bin/env python3
"""Summarize current J1 JUnit reports without running a device, account or test."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def counts(path):
    root = ET.parse(path).getroot()
    return {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}


def main():
    suites = {
        "tools/framework/build/test-results/test": [
            "JobObservationServiceTest", "JobObservationDispatchTest",
            "JobObservationIsolationTest", "JobObservationLimitsTest",
        ],
        "app/build/test-results/testDeveloperDebugUnitTest": [
            "LinuxJobObservationPortTest", "BackgroundJobActionsTest",
        ],
        "app/build/test-results/testConsumerDebugUnitTest": ["BackgroundJobActionsTest"],
    }
    result = {}
    for directory, names in suites.items():
        paths = list(Path(directory).glob("TEST-*.xml"))
        for name in names:
            matches = [path for path in paths if path.name.endswith("." + name + ".xml")]
            if len(matches) != 1:
                raise RuntimeError(f"Expected exactly one report: {directory}/{name}")
            path = matches[0]
            result[str(path)] = counts(path)
            result[str(path)]["reportSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    totals = {}
    for directory in list(suites) + ["core/agent/build/test-results/test"]:
        paths = list(Path(directory).glob("TEST-*.xml"))
        if not paths:
            raise RuntimeError(f"Missing reports in {directory}")
        total = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
        for path in paths:
            for key, value in counts(path).items():
                total[key] += value
        totals[directory] = {"suites": len(paths), **total}
    output = {"targeted": result, "reportTotals": totals}
    text = json.dumps(output, indent=2)
    Path("build/hxa236-test-summary.json").write_text(text + "\n")
    print(text)
    return int(any(v["failures"] or v["errors"] or not v["tests"] for v in result.values()))


if __name__ == "__main__":
    raise SystemExit(main())

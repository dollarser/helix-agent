"""Summarize explicit current JUnit reports; command evidence establishes freshness."""
from __future__ import annotations

import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOTS = {
    "app-consumer": "app/build/test-results/testConsumerDebugUnitTest",
    "app-developer": "app/build/test-results/testDeveloperDebugUnitTest",
    "cli-app": "runtime/cli-app/build/test-results/testDebugUnitTest",
    "cli-client": "runtime/cli-client/build/test-results/testDebugUnitTest",
    "proot-app": "runtime/proot-app/build/test-results/testDebugUnitTest",
    "proot-client": "runtime/proot-client/build/test-results/testDebugUnitTest",
}
FOCUSED = {
    "PostCommitCancellationAuditTest", "SubscriptionCancellationTest", "SubscriptionAckIntegrationTest",
    "CliJobRecordFileTest", "CliResultAckQueueTest", "ProotProcessOwnerTest", "ProotJobAwaiterTest",
    "SubscriptionAckCollectionTest",
}
FIELDS = ("tests", "failures", "errors", "skipped")


def main() -> None:
    result = {}
    seen = set()
    for module, directory in ROOTS.items():
        reports = sorted(Path(directory).glob("TEST-*.xml"))
        if not reports:
            raise SystemExit(f"Missing reports: {directory}")
        totals = dict.fromkeys(FIELDS, 0)
        focused = []
        for report in reports:
            suite = ET.parse(report).getroot()
            counts = {field: int(suite.get(field, "0")) for field in FIELDS}
            for field in FIELDS:
                totals[field] += counts[field]
            name = suite.get("name", "").rsplit(".", 1)[-1]
            if name in FOCUSED:
                seen.add(name)
                if not counts["tests"] or counts["skipped"]:
                    raise SystemExit(f"Missing execution of focused regression: {name}")
                focused.append({"suite": name, "timestamp": suite.get("timestamp"), **counts})
        if not totals["tests"] or totals["failures"] or totals["errors"]:
            raise SystemExit(f"Unsuccessful reports: {module}: {totals}")
        result[module] = {"directory": directory, **totals, "focused": focused}
    if seen != FOCUSED:
        raise SystemExit(f"Missing focused suites: {sorted(FOCUSED - seen)}")
    output = Path("build/post-commit-fixes-2026-09-30/host-summary.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    output.write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()

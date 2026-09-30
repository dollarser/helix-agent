"""Summarize exact current test directories; never count archived build trees."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET

DIRECTORIES = [
    "app/build/test-results/testConsumerDebugUnitTest",
    "app/build/test-results/testDeveloperDebugUnitTest",
    "runtime/quickjs/build/test-results/testDebugUnitTest",
    "runtime/proot-app/build/test-results/testDebugUnitTest",
    "runtime/proot-client/build/test-results/testDebugUnitTest",
    "runtime/proot-core/build/test-results/test",
    "runtime/proot-ipc/build/test-results/testDebugUnitTest",
    "runtime/cli-app/build/test-results/testDebugUnitTest",
    "runtime/cli-client/build/test-results/testDebugUnitTest",
    "tools/framework/build/test-results/test",
]
FOCUSED = {
    "NativeJavascriptOwnershipTest", "ForegroundProotOwnershipTest",
    "SubscriptionCollectionPolicyTest", "SubscriptionCancellationTruthTest",
    "ProotRecoveryIdentityTest", "ProotFaultTruthTest", "LocalRuntimeCallsTest",
    "RuntimeOwnerDiskRecoveryTest", "CliModelJobAwaiterTest", "CodexPayloadJobTest",
}
FIELDS = ("tests", "failures", "errors", "skipped")


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def main() -> None:
    reports = []
    for directory in DIRECTORIES:
        files = sorted(Path(directory).glob("TEST-*.xml"))
        if not files:
            reports.append({"directory": directory, "status": "no_reports"})
            continue
        totals = dict.fromkeys(FIELDS, 0)
        focused = []
        for path in files:
            suite = ET.parse(path).getroot()
            values = {key: int(suite.get(key, "0")) for key in FIELDS}
            for key in FIELDS:
                totals[key] += values[key]
            name = suite.get("name", "")
            if name.rsplit(".", 1)[-1] in FOCUSED:
                focused.append({"suite": name, "timestamp": suite.get("timestamp"), **values})
        reports.append({"directory": directory, **totals, "focused": focused})
    apks = [
        {"path": str(path), "size_bytes": path.stat().st_size, "sha256": digest(path)}
        for path in sorted(Path("app/build/outputs/apk").rglob("*.apk"))
        if "debug" in path.parts and ("consumer" in path.parts or "developer" in path.parts)
    ]
    output = Path("build/runtime-fault-matrix-2026-09-30/host-summary.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    result = {"note": "Report counts; the recorded Gradle commands establish execution/currentness, not these files alone.",
              "reports": reports, "debug_apks": apks}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

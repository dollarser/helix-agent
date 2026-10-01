#!/usr/bin/env python3
"""Check existing follow-up reports and APK identities. No device, network or code execution."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    suites = {
        "core/agent/build/test-results/test": ["JobContextCompilerTest"],
        "tools/framework/build/test-results/test": ["JobObservationEvidenceTest", "JobObservationDispatchTest"],
        "app/build/test-results/testConsumerDebugUnitTest": ["JobProgressIntegrationTest"],
        "app/build/test-results/testDeveloperDebugUnitTest": ["JobProgressIntegrationTest", "TerminalStartTransactionTest"],
    }
    reports = {}
    for directory, names in suites.items():
        for name in names:
            matches = list(Path(directory).glob("TEST-*." + name + ".xml"))
            assert len(matches) == 1, (directory, name)
            path = matches[0]
            root = ET.parse(path).getroot()
            counts = {key: int(root.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
            assert counts["tests"] > 0 and not any(counts[k] for k in ("failures", "errors", "skipped")), path
            reports[str(path)] = {**counts, "sha256": sha(path)}
    apks = {}
    for flavor in ("consumer", "developer"):
        for path in (Path(f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"),
                     Path(f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk")):
            apks[str(path)] = {"bytes": path.stat().st_size, "sha256": sha(path)}
    resources = []
    for locale in ("values", "values-en", "values-zh-rCN"):
        path = Path(f"app/src/developer/res/{locale}/terminal.xml")
        nodes = ET.parse(path).getroot().findall("string")
        keys = [node.attrib["name"] for node in nodes]
        assert len(keys) == len(set(keys)), path
        assert {"terminal_execution_busy_help", "terminal_settlement_required"} <= set(keys)
        resources.append(set(keys))
    assert resources[0] == resources[1] == resources[2]
    result = {"reports": reports, "apks": apks, "terminalResourceKeys": len(resources[0])}
    text = json.dumps(result, indent=2) + "\n"
    Path("build/hxa236-followup/summary.json").write_text(text)
    print(text)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Recheck completed device batches; preserve skips and keep product acceptance a separate decision."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "scripts"))
from owned_acceptance import collect_owned, sha256, test_records

spec = importlib.util.spec_from_file_location("matrix", Path(__file__).with_name("run-acceptance-matrix.py"))
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--input", type=Path, action="append", required=True, help="Completed roots, oldest first")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--test-apk-directory", type=Path,
                    help="Preserved executed test APKs when later unrelated test fixtures changed; selected source hashes must still match")
args = parser.parse_args()
selected = {}
for directory in args.input:
    directory = directory.resolve()
    for report in sorted(directory.glob("*-report.json")):
        selected[report.name.removesuffix("-report.json")] = report
expected_labels = {f"{flavor}-api{api}-{group}" for flavor in ("consumer", "developer")
                   for api in (29, 36) for group in matrix.PRODUCT}
if set(selected) != expected_labels:
    raise ValueError(f"Coverage mismatch: missing={sorted(expected_labels - selected.keys())}, extra={sorted(selected.keys() - expected_labels)}")
common_skips = {"seedExtensionJourneyScope", "recoverExtensionJourneyScope"}
consumer_skips = {"enabledAndCallSucceedsWithReadableResult", "enabledButCallFails", "aFailedMcpIsRepairedInPlace",
                  "aDisabledMcpToolIsRefusedAtEverySurface", "anMcpOperationAsksForApprovalUnderReadOnly",
                  "cancelledOrUnavailableRepairKeepsViewRecoverable"}
batches = []
final_apks = {
    flavor: {
        "app": sha256(root / f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"),
        "test": sha256(args.test_apk_directory / f"app-{flavor}-debug-androidTest.apk" if args.test_apk_directory else
                       root / f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk"),
    } for flavor in ("consumer", "developer")
}
for label, report_path in sorted(selected.items()):
    flavor, api, group = re.fullmatch(r"(consumer|developer)-api(29|36)-(.+)", label).groups()
    directory = report_path.parent / label
    plan = json.loads((report_path.parent / f"{label}-expected.json").read_text())
    report = collect_owned(directory, plan["methods"])
    device = json.loads((directory / "device.json").read_text())
    assert device["api"] == int(api) and device["kind"] == "owned-emulator", "Device identity differs from matrix"
    for path, digest in plan["source_sha256"].items():
        assert sha256(root / path) == digest, f"Test source changed since execution: {path}"
    assert report["artifacts"] == final_apks[flavor], "Batch used another final APK"
    assert report["counts"]["failed"] == report["counts"]["incomplete"] == 0
    for method in report["tests"]:
        if method["status"] == "skipped":
            allowed = common_skips if group == "extensions" else set()
            if flavor == "consumer":
                allowed = allowed | consumer_skips
            assert method["test_method"] in allowed, f"Unexpected skip: {label}/{method}"
    recovery = None
    if group == "extensions-restart":
        raw = directory / "extension-recovery-logcat.txt"
        records = test_records(raw.read_text())
        key = "com.helix.app.ExtensionJourneyDeviceTest#recoverExtensionJourneyScope"
        assert set(records) == {key} and records[key]["status"] == "passed"
        identity = json.loads((directory / "extension-log-phases.json").read_text())
        assert sha256(raw) == identity["verificationSha256"]
        assert sha256(directory / "extension-recovery-all-phases.txt") == identity["allPhasesSha256"]
        recovery = {"method": key, "status": "passed", "source_sha256": sha256(raw)}
    batches.append({"batch": label, "source": str(directory.relative_to(root)), "device": device,
                    "counts": report["counts"], "tests": report["tests"], "artifacts": report["artifacts"],
                    "separate_recovery": recovery})
counts = {key: sum(batch["counts"][key] for batch in batches)
          for key in ("expected", "observed", "passed", "failed", "skipped", "incomplete")}
result = {"scope": "52 owned instrumentation batches; method counts are not product success rate",
          "counts": counts, "separate_recovery_passed": sum(bool(b["separate_recovery"]) for b in batches),
          "batches": batches}
args.output.mkdir(parents=True, exist_ok=False)
(args.output / "matrix.json").write_text(json.dumps(result, indent=2) + "\n")
rows = ["# Product device matrix", "", "Method counts, not a whole-product acceptance verdict.", "",
        "| Batch | Passed | Failed | Skipped |", "| --- | ---: | ---: | ---: |"]
rows += [f"| {b['batch']} | {b['counts']['passed']} | {b['counts']['failed']} | {b['counts']['skipped']} |" for b in batches]
(args.output / "matrix.md").write_text("\n".join(rows) + "\n")
print(json.dumps({"counts": counts, "separate_recovery_passed": result["separate_recovery_passed"]}))

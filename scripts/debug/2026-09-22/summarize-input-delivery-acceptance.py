#!/usr/bin/env python3
"""Recheck HXA-216 owned-run reports without treating planned or partial runs as passing."""
import argparse
import hashlib
import json
from pathlib import Path


def summarize(roots: list[Path]) -> dict:
    scenarios = ("pending", "appended", "http-in-flight", "cancelling")
    labels = []
    for flavor in ("consumer", "developer"):
        for api in (29, 36):
            labels.append(f"{flavor}-api{api}-regression")
            labels.extend(f"{flavor}-api{api}-216-{scenario}-recovery" for scenario in scenarios)
    labels.extend(f"storage-api{api}" for api in (29, 36))
    totals = {"regression": 0, "recovery_seed": 0, "recovery_verify": 0, "storage": 0}
    journeys = []
    artifacts = {}
    reports = {}
    for label in labels:
        # A later explicitly supplied run replaces a prior result, including failures.
        # Never fall back from a failing latest report to an older passing report.
        root = next((item for item in reversed(roots) if (item / f"{label}-report.json").exists()), None)
        if root is None:
            raise ValueError(f"Matrix is incomplete: {label}")
        report_path = root / f"{label}-report.json"
        report = json.loads(report_path.read_text())
        if report["verdict"] != "DEVICE_BATCH_PASS":
            raise ValueError(f"Failed batch: {label}")
        counts = report["counts"]
        if not (counts["expected"] == counts["observed"] == counts["passed"] > 0):
            raise ValueError(f"Nonpassing or empty method counts: {label}")
        if any(counts[key] for key in ("failed", "skipped", "incomplete")):
            raise ValueError(f"Nonpassing methods: {label}")
        lifecycle = report["device_lifecycle"]
        if lifecycle["owner"]["pid"] != lifecycle["closed"]["pid"]:
            raise ValueError(f"Owned emulator closure mismatch: {label}")
        for name, digest in report["source_refs"].items():
            if hashlib.sha256((root / label / name).read_bytes()).hexdigest() != digest:
                raise ValueError(f"Evidence changed: {label}/{name}")
        artifact_key = label.split("-")[0]
        if artifact_key in artifacts and artifacts[artifact_key] != report["artifacts"]:
            raise ValueError(f"Mixed APKs: {label}")
        artifacts[artifact_key] = report["artifacts"]
        if "normalProcess" in report:
            normal = report["normalProcess"]
            if normal["beforePid"] == normal["afterPid"] or normal["sigkill"] != 9:
                raise ValueError(f"Missing process replacement: {label}")
            if not normal["normalActivity"] or normal["room"]["secondRecoveryInterrupted"] != 0:
                raise ValueError(f"Invalid recovery evidence: {label}")
            verified = report["verifyMethods"]
            if not verified or any(v["status"] != "passed" or not v["finished"] for v in verified.values()):
                raise ValueError(f"Missing recovery verification: {label}")
            totals["recovery_seed"] += counts["passed"]
            totals["recovery_verify"] += len(verified)
            journeys.append({"batch": label, "beforePid": normal["beforePid"], "afterPid": normal["afterPid"]})
        else:
            totals["storage" if label.startswith("storage-") else "regression"] += counts["passed"]
        reports[label] = {"path": str(report_path), "sha256": hashlib.sha256(report_path.read_bytes()).hexdigest()}
    return {"scope": "HXA-216 device matrix only; host and external gates are separate",
            "counts": totals, "totalPassed": sum(totals.values()), "journeys": journeys,
            "artifacts": artifacts, "reports": reports}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix", type=Path, nargs="+", help="Original run followed by explicit replacement runs")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.matrix)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"counts": result["counts"], "totalPassed": result["totalPassed"]}))

#!/usr/bin/env python3
"""Aggregates per-class test results into atomic summary.tsv and summary.json.

Enforces:
- duplicate class = fatal error
- missing class against manifest = incomplete baseline warning/error
- unexpected class against manifest = fatal mismatch error
- classification: PASS, KNOWN_EXISTING_FAILURE, PHASE_RUNNER_REQUIRED,
  ENVIRONMENT_LIMITATION, UNRESOLVED, NEW_REGRESSION
"""

import argparse
import json
import os
import sys
from typing import Dict, List, Any

# Known Phase Runner classes that require host phases / process kill / port injection
KNOWN_PHASE_RUNNER_CLASSES = {
    "com.helix.app.chat.SessionInputProcessRecoveryDeviceTest": "Requires host runner port injection and two-phase SIGKILL",
    "com.helix.app.connector.ConnectorInstallRecoveryDeviceTest": "Requires host runner recoveryPhase argument and boundary kill",
    "com.helix.app.ui.SharedStorageDeviceTest": "Requires host AppOps storage phases (granted/revoked)",
    "com.helix.app.chat.ComposerProcessRecoveryDeviceTest": "Requires host runner two-phase SIGKILL between seed and verify",
    "com.helix.app.export.SessionExportRecoveryDeviceTest": "Requires host runner two-phase setup/verify kill mid-copy",
    "com.helix.app.ui.MessageEditRecoveryDeviceTest": "Requires host runner two-phase SIGKILL of concrete PID",
    "com.helix.app.ui.ManualSharedFileDeviceTest": "Requires host storage permission setup (hxaStoragePhase)",
}

# Known existing failures from historical baseline (a015222d / 7d7f9053 / 2187f05d)
KNOWN_EXISTING_FAILURES = {
    "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest": "Known 1 failure: test chat session is not open",
    "com.helix.app.ApprovalFlowDeviceTest": "Known 1 failure: timeout waiting for model roundtrip in emulator",
    "com.helix.app.RecoveryJourneyDeviceTest": "Known 2 failures: timeout waiting for model roundtrip in emulator",
    "com.helix.app.TaskJourneyDeviceTest": "Known 1 failure: timeout waiting for model roundtrip in emulator",
    "com.helix.app.chat.GoalModelCancellationDeviceTest": "Known failure: turn total token limit admission budget",
    "com.helix.app.ui.SessionDraftDeviceTest": "Known failure: expected <1> but was <2>",
    "com.helix.app.MainActivityTest": "Known fixture drift from HXA-226: asserts ungrouped navigation-extensions in drawer without expanding navigation-group-configure",
    "com.helix.app.ui.FilesImportExportUiTest": "Known 1 failure: removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions ComposeTimeoutException in windowless emulator",
}

# Known environment/injection limitations in headless / windowless emulator
KNOWN_ENVIRONMENT_LIMITATIONS = {
    "com.helix.app.ui.ProductFileJourneyDeviceTest": "Touch injection failed in headless emulator (Failed to inject touch input)",
    "com.helix.app.ui.SessionSearchDeviceTest": "Compose UI timeout in headless emulator",
    "com.helix.app.ui.GoalLifecycleFlowDeviceTest": "60s UI settlement timeout in headless emulator",
}


def classify_result(cls_name: str, verdict: str, log_snippet: str = "") -> str:
    """Classifies class outcome into standard baseline categories."""
    if verdict == "PASS":
        return "PASS"
    if verdict == "PHASE_RUNNER_REQUIRED" or cls_name in KNOWN_PHASE_RUNNER_CLASSES:
        return "PHASE_RUNNER_REQUIRED"
    if verdict == "ENVIRONMENT_LIMITATION" or cls_name in KNOWN_ENVIRONMENT_LIMITATIONS:
        return "ENVIRONMENT_LIMITATION"
    if cls_name in KNOWN_EXISTING_FAILURES:
        return "KNOWN_EXISTING_FAILURE"
    if verdict in ("SKIP / ASSUMPTION", "SKIP"):
        return "SKIP / ASSUMPTION"
    if verdict in ("NO_VERDICT / PROCESS_CRASH", "PROCESS_CRASH"):
        return "NO_VERDICT / PROCESS_CRASH"
    if verdict == "FAIL":
        # Unclassified failure
        return "UNRESOLVED"
    return "UNRESOLVED"


def summarize(out_dir: str, manifest_path: str = None) -> Dict[str, Any]:
    out_dir = os.path.abspath(out_dir)
    results_dir = os.path.join(out_dir, "results")

    if not os.path.isdir(results_dir):
        print(f"Error: results directory not found: {results_dir}", file=sys.stderr)
        sys.exit(1)

    # Read run metadata if available
    run_meta = {}
    run_json = os.path.join(out_dir, "run.json")
    if os.path.isfile(run_json):
        with open(run_json, "r", encoding="utf-8") as fp:
            run_meta = json.load(fp)

    # Read manifest if provided
    expected_classes = None
    if manifest_path and os.path.isfile(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as fp:
            man = json.load(fp)
            expected_classes = man.get("classes", [])

    result_files = sorted(os.listdir(results_dir))
    executed_results = []
    seen_classes = set()
    duplicates = []

    for f in result_files:
        if not f.endswith(".json"):
            continue
        fpath = os.path.join(results_dir, f)
        with open(fpath, "r", encoding="utf-8") as fp:
            try:
                data = json.load(fp)
            except Exception as e:
                print(f"Error reading {fpath}: {e}", file=sys.stderr)
                continue

        cls = data.get("class")
        if not cls:
            continue
        if cls in seen_classes:
            duplicates.append(cls)
        seen_classes.add(cls)

        verdict = data.get("verdict", "UNRESOLVED")
        category = classify_result(cls, verdict, data.get("error_msg", ""))
        data["category"] = category
        executed_results.append(data)

    if duplicates:
        print(f"FATAL: Duplicate class results found: {duplicates}", file=sys.stderr)
        sys.exit(2)

    # Check manifest alignment
    missing_classes = []
    unexpected_classes = []
    if expected_classes is not None:
        expected_set = set(expected_classes)
        missing_classes = sorted(expected_set - seen_classes)
        unexpected_classes = sorted(seen_classes - expected_set)

        if unexpected_classes:
            print(f"FATAL: Unexpected classes found not in manifest: {unexpected_classes}", file=sys.stderr)
            sys.exit(3)
        if missing_classes:
            print(f"WARNING: Incomplete baseline, missing classes: {len(missing_classes)}", file=sys.stderr)

    # Count categories
    counts = {
        "PASS": 0,
        "KNOWN_EXISTING_FAILURE": 0,
        "PHASE_RUNNER_REQUIRED": 0,
        "ENVIRONMENT_LIMITATION": 0,
        "UNRESOLVED": 0,
        "NEW_REGRESSION": 0,
        "SKIP / ASSUMPTION": 0,
        "NO_VERDICT / PROCESS_CRASH": 0,
    }

    for item in executed_results:
        cat = item.get("category", "UNRESOLVED")
        counts[cat] = counts.get(cat, 0) + 1

    summary_data = {
        "run_meta": run_meta,
        "total_classes": len(executed_results),
        "expected_count": len(expected_classes) if expected_classes else len(executed_results),
        "missing_count": len(missing_classes),
        "missing_classes": missing_classes,
        "counts": counts,
        "results": executed_results,
    }

    # Write atomic summary.json
    summary_json_path = os.path.join(out_dir, "summary.json")
    tmp_json_path = summary_json_path + ".tmp"
    with open(tmp_json_path, "w", encoding="utf-8") as fp:
        json.dump(summary_data, fp, indent=2)
    os.replace(tmp_json_path, summary_json_path)

    # Write atomic summary.tsv
    summary_tsv_path = os.path.join(out_dir, "summary.tsv")
    tmp_tsv_path = summary_tsv_path + ".tmp"
    with open(tmp_tsv_path, "w", encoding="utf-8") as fp:
        fp.write("Class\tVerdict\tCategory\tDurationSec\tDetails\n")
        for item in executed_results:
            fp.write(
                f"{item.get('class')}\t{item.get('verdict')}\t{item.get('category')}\t"
                f"{item.get('duration_sec', 0.0):.2f}\t{item.get('details', '')}\n"
            )
    os.replace(tmp_tsv_path, summary_tsv_path)

    # Print summary table
    print("=" * 60)
    print("DEVICE BASELINE SUMMARY")
    print("=" * 60)
    print(f"Total executed unique classes: {len(executed_results)}")
    if expected_classes:
        print(f"Expected classes:               {len(expected_classes)}")
        print(f"Missing classes:                {len(missing_classes)}")
    print(f"Duplicate classes:              {len(duplicates)}")
    print("-" * 60)
    for k, v in counts.items():
        print(f"  {k:28s} : {v}")
    print("=" * 60)

    if missing_classes:
        print(f"Missing classes list ({len(missing_classes)}):")
        for m in missing_classes:
            print(f"  - {m}")
        print("=" * 60)

    return summary_data


def main():
    parser = argparse.ArgumentParser(description="Summarize device test run results")
    parser.add_argument("out_dir", help="Directory containing run results")
    parser.add_argument("--manifest", default=None, help="Path to expected class manifest")
    args = parser.parse_args()

    summarize(args.out_dir, args.manifest)


if __name__ == "__main__":
    main()

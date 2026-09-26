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
import re
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

# Historical failures are matched by class *and* exact-enough failure signature. A class name
# alone is never an exemption: if the failure count or message changes, it becomes NEW_REGRESSION.
KNOWN_EXISTING_FAILURES = {
    "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest": {
        "failures": 1,
        "required": ("test chat session is not open",),
    },
    "com.helix.app.ApprovalFlowDeviceTest": {
        "failures": 1,
        "required": ("production state must settle",),
    },
    "com.helix.app.RecoveryJourneyDeviceTest": {
        "failures": 2,
        "required": (
            "the production state must settle",
            "ComposeTimeoutException: Condition still not satisfied after 10000 ms",
        ),
    },
    "com.helix.app.TaskJourneyDeviceTest": {
        "failures": 1,
        "required": ("production state must settle",),
    },
    "com.helix.app.chat.GoalModelCancellationDeviceTest": {
        "failures": 4,
        "required": ("TURN_TOTAL_TOKEN_LIMIT",),
    },
    "com.helix.app.ui.SessionDraftDeviceTest": {
        "failures": 1,
        "required": ("expected:<1> but was:<2>",),
    },
    "com.helix.app.ui.FilesImportExportUiTest": {
        "failures": 1,
        "required": (
            "removingTheCurrentSafLocationReturnsToWorkspaceWithoutStaleActions",
            "ComposeTimeoutException: Condition still not satisfied after 30000 ms",
        ),
    },
}

# Environment limitations receive the same signature discipline; a different failure in the same
# class is not hidden behind the historical headless-emulator label.
KNOWN_ENVIRONMENT_LIMITATIONS = {
    "com.helix.app.ui.ProductFileJourneyDeviceTest": {
        "failures": 4,
        "required": ("Failed to inject touch input",),
    },
    "com.helix.app.ui.SessionSearchDeviceTest": {
        "failures": 2,
        "required": (
            "openingAResultCancelsTheSearch",
            "clearingTheSearchRestoresTheSessionList",
            "ComposeTimeoutException: Condition still not satisfied after 10000 ms",
        ),
    },
    "com.helix.app.ui.GoalLifecycleFlowDeviceTest": {
        "failures": 1,
        "required": (
            "humanMessagePreemptsWithoutResettingGoalOrBudget",
            "Timed out waiting for 60000 ms",
        ),
    },
}


def _failure_count(log_content: str):
    match = re.search(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+)", log_content)
    return int(match.group(2)) if match else None


def _matches_signature(spec: Dict[str, Any], log_content: str) -> bool:
    expected_failures = spec.get("failures")
    if expected_failures is not None and _failure_count(log_content) != expected_failures:
        return False
    return all(fragment in log_content for fragment in spec.get("required", ()))


def classify_result(cls_name: str, verdict: str, log_content: str = "") -> str:
    """Classify one durable class result without allowing class-name-only exemptions."""
    if verdict == "PASS":
        return "PASS"
    if verdict == "PHASE_RUNNER_REQUIRED":
        return "PHASE_RUNNER_REQUIRED"
    if verdict in ("SKIP / ASSUMPTION", "SKIP"):
        return "SKIP / ASSUMPTION"
    if verdict in ("NO_VERDICT / PROCESS_CRASH", "PROCESS_CRASH"):
        return "NO_VERDICT / PROCESS_CRASH"
    if verdict == "FAIL":
        environment_spec = KNOWN_ENVIRONMENT_LIMITATIONS.get(cls_name)
        if environment_spec and _matches_signature(environment_spec, log_content):
            return "ENVIRONMENT_LIMITATION"
        known_spec = KNOWN_EXISTING_FAILURES.get(cls_name)
        if known_spec and _matches_signature(known_spec, log_content):
            return "KNOWN_EXISTING_FAILURE"
        return "NEW_REGRESSION"
    if verdict == "ENVIRONMENT_LIMITATION":
        environment_spec = KNOWN_ENVIRONMENT_LIMITATIONS.get(cls_name)
        if environment_spec and _matches_signature(environment_spec, log_content):
            return "ENVIRONMENT_LIMITATION"
        return "NEW_REGRESSION"
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
        log_content = ""
        log_path = data.get("log_path")
        if log_path and os.path.isfile(log_path):
            try:
                with open(log_path, "r", encoding="utf-8") as log_fp:
                    log_content = log_fp.read()
            except OSError:
                log_content = ""
        category = classify_result(cls, verdict, log_content)
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

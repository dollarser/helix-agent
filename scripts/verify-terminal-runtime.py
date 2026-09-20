#!/usr/bin/env python3
"""HXA-199 Terminal Runtime Acceptance and Verification.

Parses and validates terminal runtime evidence (HXA-195 to HXA-198 scenarios,
performance metrics, and hardware/stress boundaries). Note: owned-device scheduling
and multi-device execution remain downstream integration duties.

Usage:
    python3 scripts/verify-terminal-runtime.py --help
    python3 scripts/verify-terminal-runtime.py --manifest <manifest.json> --output <output-directory>
"""
import argparse
import json
from pathlib import Path
import sys
from typing import Any, Dict, List, Set

SCRIPTS_DIR = Path(__file__).resolve().parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from acceptance_reports import (
    BatchIdentity,
    DeviceLifecycle,
    EvidenceError,
    MAX_ENTRIES,
    MAX_FILE_BYTES,
    SceneRecord,
    TestCounts,
    build_markdown_report,
    require,
    safe_load_json,
)

HXA_199_MANDATORY_SCENES: Set[str] = {
    "realtime_logs_and_stop",
    "background_reclaim",
    "single_terminal_repl",
    "dual_session_pty",
    "page_switch_reconnect",
    "process_death_reconciliation",
    "legacy_data_upgrade",
    "consumer_exclusion",
}

STRESS_BOUNDARIES: Set[str] = {
    "detach_idle_30m",
    "full_lease_2h",
    "runtime_lease_expiration",
    "oem_doze_thermal_pressure",
    "real_16k_pagesize",
}


def verify_terminal_runtime(manifest_path: Path, output_dir: Path) -> int:
    resolved_manifest = manifest_path.resolve()
    manifest_dir = resolved_manifest.parent
    data = safe_load_json(resolved_manifest, base_dir=manifest_dir, max_bytes=MAX_FILE_BYTES)

    # 1. Identity & Scope
    scope = data.get("scope")
    require(scope == "HXA-199", f"Expected scope 'HXA-199', got: '{scope}'", exit_code=2)

    identity = BatchIdentity(
        schema_version=data.get("schema_version", 0),
        scope=scope,
        commit_sha=data.get("commit_sha", ""),
        mode=data.get("mode", ""),
        api=data.get("api"),
        flavor=data.get("flavor"),
        app_apk_sha256=data.get("app_apk_sha256"),
        test_apk_sha256=data.get("test_apk_sha256"),
    )
    identity.validate()

    # 2. Device Lifecycle
    lifecycle_raw = data.get("device_lifecycle")
    require(isinstance(lifecycle_raw, dict), "Missing or invalid 'device_lifecycle' object", exit_code=2)
    lifecycle = DeviceLifecycle(
        owner_pid=lifecycle_raw.get("owner_pid"),
        closed=lifecycle_raw.get("closed", False),
        details=lifecycle_raw.get("details", {}),
    )
    lifecycle.validate(identity.mode)

    # 3. Test Counts
    counts_raw = data.get("counts")
    require(isinstance(counts_raw, dict), "Missing or invalid 'counts' object", exit_code=2)
    counts = TestCounts(
        expected=counts_raw.get("expected", 0),
        executed=counts_raw.get("executed", 0),
        passed=counts_raw.get("passed", 0),
        failed=counts_raw.get("failed", 0),
        skipped=counts_raw.get("skipped", 0),
    )
    counts.validate()

    # 4. Scenes validation
    scenes_raw = data.get("scenes")
    require(isinstance(scenes_raw, list), "Missing or invalid 'scenes' array", exit_code=2)
    require(len(scenes_raw) <= MAX_ENTRIES, f"Exceeded max entries limit {MAX_ENTRIES}", exit_code=2)

    seen_scene_ids: Set[str] = set()
    scenes: List[SceneRecord] = []
    observed_passed = 0
    observed_failed = 0
    observed_skipped = 0

    has_incomplete_core = False

    for s_item in scenes_raw:
        require(isinstance(s_item, dict), "Scene item must be a JSON object", exit_code=2)
        sid = s_item.get("scene_id", "")
        require(sid not in seen_scene_ids, f"Duplicate scene_id detected: {sid}", exit_code=2)
        seen_scene_ids.add(sid)

        metrics = s_item.get("metrics", {})
        require(isinstance(metrics, dict), f"Metrics for scene {sid} must be an object", exit_code=2)

        status = s_item.get("status", "")
        skip_reason = s_item.get("skip_reason")

        # Specific rule checks
        if sid == "dual_session_pty" and status in ("skipped", "incomplete"):
            has_incomplete_core = True

        if sid == "full_lease_2h" and status == "passed":
            duration = metrics.get("duration_seconds")
            require(
                isinstance(duration, (int, float)) and duration >= 7200,
                f"full_lease_2h requires duration_seconds >= 7200, got: {duration}",
                exit_code=2,
            )

        if sid == "detach_idle_30m" and status == "passed":
            duration = metrics.get("duration_seconds")
            require(
                isinstance(duration, (int, float)) and duration >= 1800,
                f"detach_idle_30m requires duration_seconds >= 1800, got: {duration}",
                exit_code=2,
            )

        if sid == "real_16k_pagesize" and status == "passed":
            page_size = metrics.get("page_size_bytes")
            require(
                page_size == 16384,
                f"real_16k_pagesize requires page_size_bytes == 16384, got: {page_size}",
                exit_code=2,
            )

        sr = SceneRecord(
            scene_id=sid,
            test_class=s_item.get("test_class", ""),
            test_method=s_item.get("test_method", ""),
            status=status,
            source_ref=s_item.get("source_ref", ""),
            skip_reason=skip_reason,
            metrics=metrics,
        )
        sr.validate()
        scenes.append(sr)

        if sr.status == "passed":
            observed_passed += 1
        elif sr.status == "failed":
            observed_failed += 1
        elif sr.status == "skipped":
            observed_skipped += 1

    require(
        counts.executed == len(scenes),
        f"Counts mismatch: counts.executed ({counts.executed}) != scenes count ({len(scenes)})",
        exit_code=2,
    )
    require(
        counts.passed == observed_passed and counts.failed == observed_failed and counts.skipped == observed_skipped,
        f"Counts mismatch: counts (P={counts.passed}, F={counts.failed}, S={counts.skipped}) "
        f"!= observed (P={observed_passed}, F={observed_failed}, S={observed_skipped})",
        exit_code=2,
    )

    # 5. Mandatory scenes coverage
    missing_mandatory = HXA_199_MANDATORY_SCENES - seen_scene_ids
    require(
        not missing_mandatory,
        f"Missing mandatory HXA-199 scenes: {sorted(missing_mandatory)}",
        exit_code=2,
    )

    # Check stress boundaries tracking
    pending_stress = STRESS_BOUNDARIES - seen_scene_ids

    # 6. Verdict and Exit Code
    if counts.failed > 0:
        verdict = "FAIL"
        exit_code = 1
    elif has_incomplete_core or counts.skipped > 0 or pending_stress:
        verdict = "INCOMPLETE" if identity.mode == "real" else "FIXTURE_INCOMPLETE"
        exit_code = 0
    elif identity.mode == "fixture":
        verdict = "FIXTURE_ONLY"
        exit_code = 0
    else:
        verdict = "PASS"
        exit_code = 0

    # 7. Output Generation
    output_dir.mkdir(parents=True, exist_ok=True)
    report_data = {
        "verdict": verdict,
        "identity": {
            "schema_version": identity.schema_version,
            "scope": identity.scope,
            "commit_sha": identity.commit_sha,
            "mode": identity.mode,
            "api": identity.api,
            "flavor": identity.flavor,
            "app_apk_sha256": identity.app_apk_sha256,
            "test_apk_sha256": identity.test_apk_sha256,
        },
        "lifecycle": {
            "owner_pid": lifecycle.owner_pid,
            "closed": lifecycle.closed,
            "details": lifecycle.details,
        },
        "counts": {
            "expected": counts.expected,
            "executed": counts.executed,
            "passed": counts.passed,
            "failed": counts.failed,
            "skipped": counts.skipped,
        },
        "scenes": [
            {
                "scene_id": s.scene_id,
                "test_class": s.test_class,
                "test_method": s.test_method,
                "status": s.status,
                "source_ref": s.source_ref,
                "skip_reason": s.skip_reason,
                "metrics": s.metrics,
            }
            for s in scenes
        ],
        "mandatory_coverage": {
            "required": sorted(HXA_199_MANDATORY_SCENES),
            "missing": sorted(missing_mandatory),
        },
        "stress_boundaries": {
            "tracked": sorted(STRESS_BOUNDARIES & seen_scene_ids),
            "pending": sorted(pending_stress),
        },
    }

    report_json_path = output_dir / "report.json"
    with report_json_path.open("w", encoding="utf-8") as f:
        json.dump(report_data, f, indent=2)

    extra_sections = {
        "Mandatory Terminal Scenarios": "\n".join(f"- `{s}`" for s in sorted(HXA_199_MANDATORY_SCENES)),
        "Stress & Hardware Boundaries": (
            "**Tracked**:\n" + "\n".join(f"- `{s}`" for s in sorted(STRESS_BOUNDARIES & seen_scene_ids)) + "\n\n"
            "**Pending**:\n" + ("\n".join(f"- `{s}`" for s in sorted(pending_stress)) if pending_stress else "None")
        ),
    }

    report_md_path = output_dir / "report.md"
    md_content = build_markdown_report(
        scope="HXA-199 Terminal Runtime",
        identity=identity,
        lifecycle=lifecycle,
        counts=counts,
        scenes=scenes,
        verdict=verdict,
        mandatory_scenes=HXA_199_MANDATORY_SCENES,
        missing_scenes=missing_mandatory,
        extra_sections=extra_sections,
    )
    with report_md_path.open("w", encoding="utf-8") as f:
        f.write(md_content)

    return exit_code


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--manifest", type=Path, required=True, help="Path to terminal manifest JSON file")
    parser.add_argument("--output", type=Path, required=True, help="Output directory to write report.json and report.md")
    args = parser.parse_args()

    try:
        return verify_terminal_runtime(args.manifest, args.output)
    except EvidenceError as e:
        sys.stderr.write(f"ERROR: {e}\n")
        return e.exit_code
    except Exception as e:
        sys.stderr.write(f"UNEXPECTED ERROR: {e}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())

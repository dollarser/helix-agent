#!/usr/bin/env python3
"""HXA-206 Product Journeys Acceptance Verification.

Parses and verifies product journey acceptance results against mandatory scene requirements.
Usage:
    python3 scripts/verify-product-journeys.py --help
    python3 scripts/verify-product-journeys.py --manifest <manifest.json> --output <output-directory>
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
    verify_source_evidence,
)
from owned_acceptance import add_owned_arguments, collect_from_arguments, verify_manifest_batch

HXA_206_MANDATORY_SCENES: Set[str] = {
    "read_modify_artifact",
    "authorization_presets_custom",
    "cancel_reboot_recovery",
    "plan_search_theme_mcp",
    "session_export_jsonl",
    "git_r1_readonly",
}


def verify_product_journeys(manifest_path: Path, output_dir: Path) -> int:
    resolved_manifest = manifest_path.resolve()
    manifest_dir = resolved_manifest.parent
    data = safe_load_json(resolved_manifest, base_dir=manifest_dir, max_bytes=MAX_FILE_BYTES)

    # 1. Identity & Scope
    scope = data.get("scope")
    require(scope == "HXA-206", f"Expected scope 'HXA-206', got: '{scope}'", exit_code=2)

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

    for s_item in scenes_raw:
        require(isinstance(s_item, dict), "Scene item must be a JSON object", exit_code=2)
        sid = s_item.get("scene_id", "")
        require(sid not in seen_scene_ids, f"Duplicate scene_id detected: {sid}", exit_code=2)
        seen_scene_ids.add(sid)

        # Validate metrics structure if present
        metrics = s_item.get("metrics", {})
        require(isinstance(metrics, dict), f"Metrics for scene {sid} must be an object", exit_code=2)

        sr = SceneRecord(
            scene_id=sid,
            test_class=s_item.get("test_class", ""),
            test_method=s_item.get("test_method", ""),
            status=s_item.get("status", ""),
            source_ref=s_item.get("source_ref", ""),
            skip_reason=s_item.get("skip_reason"),
            metrics=metrics,
        )
        sr.validate()
        verify_source_evidence(
            source_ref=sr.source_ref,
            base_dir=manifest_dir,
            mode=identity.mode,
            declared_status=sr.status,
            test_class=sr.test_class,
            test_method=sr.test_method,
            metrics=sr.metrics,
        )
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

    verify_manifest_batch(data, manifest_dir)

    # 5. Mandatory scenes coverage
    missing_mandatory = HXA_206_MANDATORY_SCENES - seen_scene_ids
    require(
        not missing_mandatory,
        f"Missing mandatory HXA-206 scenes: {sorted(missing_mandatory)}",
        exit_code=2,
    )

    # 6. Verdict and Exit Code
    if counts.failed > 0:
        verdict = "FAIL"
        exit_code = 1
    elif identity.mode == "fixture":
        verdict = "FIXTURE_ONLY" if counts.skipped == 0 else "FIXTURE_INCOMPLETE"
        exit_code = 0
    else:
        if counts.skipped > 0 or missing_mandatory:
            verdict = "INCOMPLETE"
            exit_code = 1
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
            "required": sorted(HXA_206_MANDATORY_SCENES),
            "missing": sorted(missing_mandatory),
        },
    }

    report_json_path = output_dir / "report.json"
    with report_json_path.open("w", encoding="utf-8") as f:
        json.dump(report_data, f, indent=2)

    report_md_path = output_dir / "report.md"
    md_content = build_markdown_report(
        scope="HXA-206 Product Journeys",
        identity=identity,
        lifecycle=lifecycle,
        counts=counts,
        scenes=scenes,
        verdict=verdict,
        mandatory_scenes=HXA_206_MANDATORY_SCENES,
        missing_scenes=missing_mandatory,
        extra_sections={
            "Mandatory Groups Checked": "\n".join(f"- `{group}`" for group in sorted(HXA_206_MANDATORY_SCENES))
        },
    )
    with report_md_path.open("w", encoding="utf-8") as f:
        f.write(md_content)

    return exit_code


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--manifest", type=Path, help="Path to input manifest JSON file")
    add_owned_arguments(parser)
    parser.add_argument("--output", type=Path, required=True, help="Output directory to write report.json and report.md")
    args = parser.parse_args()

    try:
        if args.owned_run:
            return collect_from_arguments(args)
        require(args.manifest is not None, "Specify --manifest or --owned-run")
        require(args.expected_methods is None, "--expected-methods requires --owned-run")
        return verify_product_journeys(args.manifest, args.output)
    except EvidenceError as e:
        sys.stderr.write(f"ERROR: {e}\n")
        return e.exit_code
    except Exception as e:
        sys.stderr.write(f"UNEXPECTED ERROR: {e}\n")
        return 2


if __name__ == "__main__":
    sys.exit(main())

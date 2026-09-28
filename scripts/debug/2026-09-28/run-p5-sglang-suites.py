#!/usr/bin/env python3
"""Run the P5 Harness system suites on one owned emulator against host SGLang."""
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import sys
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[3]
SUITES = ("files", "javascript", "skills", "goal")
PREFIXES = {"files": "file-", "javascript": "js-", "skills": "skill-", "goal": "goal-"}
DATASET = ROOT / "evals/m10/fixed-evals.tsv"


def percentile(values, fraction):
    if not values:
        return None
    rows = sorted(values)
    index = max(0, min(len(rows) - 1, int((len(rows) * fraction + 0.999999)) - 1))
    return rows[index]


def main():
    serial, output = sys.argv[1:]
    out = Path(output)
    with urllib.request.urlopen("http://127.0.0.1:30008/v1/models", timeout=10) as response:
        model_list = json.load(response)
    with urllib.request.urlopen("http://127.0.0.1:30008/get_model_info", timeout=10) as response:
        model_info = json.load(response)
    model = model_list["data"][0]["id"]
    if model != "Qwen3.8-27B":
        raise RuntimeError(f"P5 expected Qwen3.8-27B, got {model!r}")

    suite_rows = []
    case_rows = []
    dataset_lines = DATASET.read_text().splitlines()
    expected = [(suite, line.split("\t", 1)[0]) for suite in SUITES for line in dataset_lines if line.startswith(PREFIXES[suite])]
    for suite, case_id in expected:
        target = out / f"sglang-{suite}-{case_id}"
        log_path = out / f"sglang-{suite}-{case_id}-host.log"
        started = time.monotonic()
        with log_path.open("w") as log:
            process = subprocess.run(
                [
                    sys.executable,
                    "scripts/run-hxa100-provider-evals.py",
                    serial,
                    "--provider-port",
                    "30008",
                    "--suite",
                    suite,
                    "--case-id",
                    case_id,
                    "--protocol-override",
                    "OPENAI_CHAT_COMPLETIONS",
                    "--output",
                    str(target),
                ],
                cwd=ROOT,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
                timeout=2700,
            )
        host_wall_ms = round((time.monotonic() - started) * 1000)
        result = json.loads((target / "result.json").read_text())
        records = [json.loads(path.read_text()) for path in sorted((target / "device").glob("*.json"))]
        if records:
            record = records[0]
            elapsed = record.get("elapsedMs", record.get("elapsedMillis"))
            calls = record.get("calls") or []
            case_rows.append(
                {
                    "suite": suite,
                    "id": record.get("id"),
                    "result": record.get("result"),
                    "elapsedMs": elapsed,
                    "turnState": record.get("turnState"),
                    "errorCode": record.get("errorCode"),
                    "toolCallCount": len(calls) if isinstance(calls, list) else None,
                    "calls": calls,
                    "runnerExitCode": process.returncode,
                    "hostWallMs": host_wall_ms,
                }
            )
        else:
            case_rows.append(
                {
                    "suite": suite,
                    "id": case_id,
                    "result": "ERROR",
                    "elapsedMs": None,
                    "turnState": None,
                    "errorCode": "INSTRUMENTATION_OR_FIXTURE_ERROR",
                    "toolCallCount": None,
                    "calls": [],
                    "runnerExitCode": process.returncode,
                    "hostWallMs": host_wall_ms,
                }
            )

    for suite in SUITES:
        rows = [row for row in case_rows if row["suite"] == suite]
        suite_rows.append(
            {
                "suite": suite,
                "passed": all(row["result"] == "PASS" for row in rows),
                "count": len(rows),
                "passCount": sum(row["result"] == "PASS" for row in rows),
                "errorCount": sum(row["result"] == "ERROR" for row in rows),
                "hostWallMs": sum(row["hostWallMs"] for row in rows),
                "expectedIds": [case for owner, case in expected if owner == suite],
            }
        )

    elapsed = [row["elapsedMs"] for row in case_rows if isinstance(row.get("elapsedMs"), int)]
    passed_cases = [row for row in case_rows if row.get("result") == "PASS"]
    source_shas = {
        json.loads((out / f"sglang-{suite}-{case_id}" / "source-manifest.json").read_text())["sha256"]
        for suite, case_id in expected
    }
    configs = [json.loads((out / f"sglang-{suite}-{case_id}" / "config.json").read_text()) for suite, case_id in expected]
    installed = {json.dumps(config.get("installedApkSha256"), sort_keys=True) for config in configs}
    summary = {
        "schemaVersion": 1,
        "purpose": "P5 Harness system baseline; local 4B is minimum-capability evidence only",
        "provider": {
            "kind": "local-sglang",
            "endpoint": "http://localhost:30008/",
            "model": model,
            "maxModelLen": model_list["data"][0].get("max_model_len"),
            "modelType": model_info.get("model_type"),
            "architectures": model_info.get("architectures"),
            "toolCallParser": model_info.get("tool_call_parser"),
            "reasoningParser": model_info.get("reasoning_parser"),
        },
        "identity": {
            "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
            "datasetSha256": hashlib.sha256(DATASET.read_bytes()).hexdigest(),
            "sourceManifestSha": next(iter(source_shas)) if len(source_shas) == 1 else None,
            "installedApkSha256": json.loads(next(iter(installed))) if len(installed) == 1 else None,
        },
        "scope": {
            "suites": list(SUITES),
            "expectedCaseCount": 15,
            "caseCount": len(case_rows),
            "allSuitesPassed": all(row["passed"] for row in suite_rows),
            "allCasesPassed": len(passed_cases) == len(case_rows) == 15,
            "baselineComplete": len(case_rows) == 15,
        },
        "performance": {
            "metric": "Harness end-to-end case elapsedMs; includes model, tool, approval and persistence work",
            "caseElapsedMsCount": len(elapsed),
            "meanMs": round(statistics.mean(elapsed), 1) if elapsed else None,
            "medianMs": round(statistics.median(elapsed), 1) if elapsed else None,
            "p95Ms": percentile(elapsed, 0.95),
            "maxMs": max(elapsed) if elapsed else None,
            "suiteHostWallMs": {row["suite"]: row["hostWallMs"] for row in suite_rows},
            "notMeasured": ["SGLang prefill", "true TTFT", "decode-only tok/s", "server GPU memory"],
        },
        "suites": suite_rows,
        "cases": case_rows,
    }
    (out / "p5-sglang-summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    if len(source_shas) != 1 or len(installed) != 1:
        raise SystemExit("P5 suites did not use one source/APK identity")
    if len(case_rows) != 15:
        raise SystemExit("P5 SGLang Harness baseline did not collect all fixed cases")


if __name__ == "__main__":
    main()

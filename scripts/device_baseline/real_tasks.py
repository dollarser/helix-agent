#!/usr/bin/env python3
"""Summarize one frozen real-task batch. Never execute, retry, or authenticate."""
import argparse
import hashlib
import json
import re
from pathlib import Path


CATEGORIES = {"AUTH", "SERVICE", "HARNESS", "CONTEXT", "TOOL", "MODEL", "UNKNOWN"}
METRICS = ("humanInterventions", "fixtureApprovals", "modelCalls", "wireRequests", "toolCalls", "elapsedMs")

REQUIRED_METRICS = tuple(name for name in METRICS if name != "wireRequests")


def load(path):
    return json.loads(path.read_text())


def artifact(root, relative):
    if not isinstance(relative, str) or not relative:
        raise ValueError("Evidence must name a relative artifact")
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Evidence missing or outside batch: {relative}")
    return path


def freeze_manifest(manifest, source):
    """Resolve public identity from the candidate source, never an account configuration."""
    if manifest.get("modelSelection") != "candidate-built-in":
        raise ValueError("Real-task manifest must select candidate-built-in")
    for case in manifest["cases"]:
        if {"helixProviderId", "helixModelId"} & case.get("arguments", {}).keys():
            raise ValueError("Per-task model overrides are forbidden")
    identity = {}
    for key, constant in (("providerId", "PROVIDER_ID"), ("modelId", "MODEL_ID")):
        matches = re.findall(r'const val ' + constant + r' = "([^"\n]+)"', source)
        if len(matches) != 1:
            raise ValueError("Candidate public model identity is ambiguous")
        identity[key] = matches[0]
    return dict(manifest, modelIdentity=identity,
                modelSourceSha256=hashlib.sha256(source.encode()).hexdigest())


def validate_model_identity(source, expected):
    calls = source.get("modelIdentities")
    if (not isinstance(calls, list) or len(calls) != source.get("modelCalls")
            or any(call != expected for call in calls)):
        raise ValueError("Missing or mixed model-call identities; not a comparable baseline")
    if source.get("taskPassed", source.get("completed")) is True and not calls:
        raise ValueError("Business pass requires observed model calls")


def summarize(manifest, batch, root):
    identity = manifest.get("modelIdentity")
    if not isinstance(identity, dict) or set(identity) != {"providerId", "modelId"} or not all(
        isinstance(value, str) and value for value in identity.values()
    ):
        raise ValueError("Freeze the candidate model identity before collecting a batch")
    if manifest != load(artifact(root, batch["manifest"])):
        raise ValueError("Supplied manifest differs from frozen manifest")
    expected = [case["id"] for case in manifest["cases"]]
    if len(expected) != len(set(expected)) or not expected:
        raise ValueError("Manifest must have unique, nonempty cases")
    rows = batch["cases"]
    ids = [row["id"] for row in rows]
    if len(ids) != len(set(ids)) or set(ids) - set(expected):
        raise ValueError("Duplicate attempts or unexpected cases; retain separate batches")
    if batch.get("manifestSha256") != hashlib.sha256(
        artifact(root, batch["manifest"]).read_bytes()
    ).hexdigest():
        raise ValueError("Frozen manifest digest mismatch")
    evidence_paths = [str(artifact(root, row["evidence"])) for row in rows if row.get("status") != "BLOCKED"]
    if len(evidence_paths) != len(set(evidence_paths)):
        raise ValueError("Each executed task requires its own evidence artifact")
    output = []
    for case_id in expected:
        row = next((item for item in rows if item["id"] == case_id), None)
        if row is None:
            output.append({"id": case_id, "status": "NOT_RUN", "businessCompleted": False,
                           "attribution": "UNKNOWN", "metrics": dict.fromkeys(METRICS)})
            continue
        if row.get("attempt") != (0 if row.get("status") == "BLOCKED" else 1):
            raise ValueError("Exactly one attempt per task per batch is required")
        if row.get("status") == "BLOCKED":
            cause = row.get("attribution", {})
            if cause.get("category") not in CATEGORIES or not cause.get("reason") or not cause.get("evidence"):
                raise ValueError("Blocked task requires a specific dependency and evidence")
            for path in cause["evidence"]:
                artifact(root, path)
            output.append({"id": case_id, "status": "BLOCKED", "businessCompleted": False,
                           "attribution": cause["category"], "reason": cause["reason"],
                           "metrics": dict.fromkeys(METRICS)})
            continue
        source = load(artifact(root, row["evidence"]))
        validate_model_identity(source, identity)
        # Only the fixture's business assertion, never Turn/JUnit completion.
        flag = source.get("taskPassed", source.get("completed"))
        if flag is not None and type(flag) is not bool:
            raise ValueError("Business assertion must be boolean")
        statuses = source.get("httpStatuses", [])
        auth = (any(code in (401, 403) for code in statuses) or source.get("errorCode") == "AUTH"
                or any(turn.get("errorCode") == "AUTH" for turn in source.get("turns", [])))
        passed = flag is True and not auth
        metrics = dict.fromkeys(METRICS)
        for name in METRICS:
            value = source.get(name)
            if value is not None and (type(value) is not int or value < 0):
                raise ValueError(f"Invalid measured {name}")
            metrics[name] = value
        calls = source.get("tools", source.get("calls"))
        if isinstance(calls, list):
            metrics["toolCalls"] = len(calls)
        cause = row.get("attribution", {})
        category = "AUTH" if auth else cause.get("category", "UNKNOWN")
        if category not in CATEGORIES:
            raise ValueError("Unknown failure attribution")
        if not passed and category not in {"UNKNOWN", "AUTH"}:
            if not cause.get("reason") or not cause.get("evidence"):
                raise ValueError("Failure attribution requires reason and evidence artifacts")
            for path in cause["evidence"]:
                artifact(root, path)
        output.append({"id": case_id, "status": "PASS" if passed else ("FAILED" if flag is not None or auth else "INCOMPLETE"),
                       "businessCompleted": passed, "attribution": None if passed else category,
                       "reason": cause.get("reason") or ("Provider reported AUTH or HTTP 401/403" if auth else None),
                       "metrics": metrics,
                       "evidence": row["evidence"],
                       "evidenceSha256": hashlib.sha256(artifact(root, row["evidence"]).read_bytes()).hexdigest()})
    completed = sum(row["businessCompleted"] for row in output)
    return {"modelIdentity": identity, "planned": len(expected), "observed": sum(row["status"] not in {"BLOCKED", "NOT_RUN"} for row in output), "businessCompleted": completed,
            "completionRateOverPlanned": completed / len(expected),
            "blocked": sum(row["status"] == "BLOCKED" for row in output),
            "modelQualityRate": None,  # A mixed infrastructure/model batch is not a model score.
            "allRequiredMetricsMeasured": all(all(row["metrics"][name] is not None for name in REQUIRED_METRICS) for row in output),
            "cases": output}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("batch", type=Path, nargs="?", help="Batch JSON with relative manifest and evidence paths")
    parser.add_argument("--output", type=Path, required=True, help="New report, never overwrite evidence")
    parser.add_argument("--freeze-manifest", type=Path, help="Freeze template using current public candidate source")
    args = parser.parse_args()
    if args.freeze_manifest:
        if args.batch:
            parser.error("Choose either freeze or summary")
        source = Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/helix/app/provider/BuiltInModel.kt"
        result = freeze_manifest(load(args.freeze_manifest), source.read_text())
        with args.output.open("x") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        return 0
    if not args.batch:
        parser.error("batch is required for summary")
    batch = load(args.batch)
    manifest = load(artifact(args.batch.parent, batch["manifest"]))
    result = summarize(manifest, batch, args.batch.parent)
    with args.output.open("x") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    return 0 if result["businessCompleted"] == result["planned"] and result["allRequiredMetricsMeasured"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

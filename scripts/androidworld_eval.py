"""Strict accounting for the opt-in Helix AndroidWorld adapter.

This module never drives a device or substitutes for the official oracle. A run
must freeze its full expected case list before executing its first case.
"""
import hashlib
import json
import math
import os
import tempfile
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    ensure_ascii=False).encode()).hexdigest()


def atomic_json(path, value):
    path = Path(path)
    # A failed serializer must not leave a shared temp path or damage the last
    # complete checkpoint. Concurrent writers never share temporary filenames.
    fd, name = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def valid_score(value):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and math.isfinite(value) and 0 <= value <= 1)


def bounded_error(error):
    # Do not format arbitrary exception objects or allocate their entire trace.
    message = error.args[0] if error.args and isinstance(error.args[0], str) else ""
    return type(error).__name__ + ": " + message[:2048]


def classify(expected, evidence, initial_oracle, final_oracle, instrumentation_ok,
             cleanup_error=None):
    """Keep oracle and turn completion independent; fail closed on stale evidence."""
    record = dict(expected)
    record.update(oracle_success=None, turn_completed=False, status="ERROR")
    if not isinstance(evidence, dict):
        return dict(record, error="missing or invalid fixture evidence")
    if any(type(evidence.get(key)) is not type(expected[key]) or evidence.get(key) != expected[key]
           for key in ("runId", "caseId", "task", "attempt", "goal")):
        return dict(record, error="evidence identity mismatch")
    record.update(turn_state=evidence.get("turnState"),
                  turn_completed=evidence.get("turnState") == "COMPLETED",
                  fixture_status=evidence.get("status"),
                  fixture_failure=evidence.get("failure"),
                  initial_oracle=initial_oracle, final_oracle=final_oracle)
    if valid_score(final_oracle):
        record["oracle_success"] = final_oracle == 1.0
    if cleanup_error or evidence.get("cleanupErrors"):
        return dict(record, error="cleanup failed", cleanup_error=cleanup_error or evidence["cleanupErrors"])
    if evidence.get("evidenceErrors"):
        return dict(record, error="fixture evidence capture failed")
    if evidence.get("status") == "BLOCKED":
        return dict(record, status="BLOCKED")
    if evidence.get("status") != "EXECUTED":
        return dict(record, error="missing or invalid final fixture status")
    if instrumentation_ok is not True or evidence.get("failure"):
        return dict(record, error="instrumentation or fixture failure")
    if not valid_score(initial_oracle) or not valid_score(final_oracle):
        return dict(record, error="missing or invalid oracle")
    if initial_oracle == 1.0 and not expected.get("allowsInitiallySatisfied", False):
        return dict(record, error="oracle satisfied before agent execution")
    if evidence.get("turnState") not in ("COMPLETED", "FAILED", "CANCELLED"):
        return dict(record, error="missing terminal turn")
    record["status"] = "PASS" if record["oracle_success"] and record["turn_completed"] else "FAIL"
    return record


class Ledger:
    """One immutable result per expected case; interruption is never a pass.

    Caller owns exclusive access to the run directory. No automatic retries or
    best-of selection: a changed candidate/configuration uses a new run directory.
    Results are authoritative and atomically persisted. Checkpoint is derived.
    """
    def __init__(self, directory, manifest):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.manifest = manifest
        cases = manifest["cases"]
        self.ids = [case["caseId"] for case in cases]
        if not self.ids or len(set(self.ids)) != len(self.ids):
            raise ValueError("expected cases must be nonempty and unique")
        if any(not key.replace("_", "").replace("-", "").isalnum() for key in self.ids):
            raise ValueError("unsafe case identity")
        for key in ("runId", "sourceSha256", "apkSha256", "testApkSha256",
                    "datasetRevision", "datasetSha256", "model", "seed"):
            if key not in manifest or manifest[key] is None:
                raise ValueError("missing frozen identity: " + key)
        self.identity = digest(manifest)
        path = self.directory / "manifest.json"
        if path.exists():
            if digest(json.loads(path.read_text())) != self.identity:
                raise ValueError("resume identity mismatch")
        else:
            atomic_json(path, manifest)
        (self.directory / "results").mkdir(exist_ok=True)

    def record(self, result):
        case = result["caseId"]
        if case not in self.ids or result["runId"] != self.manifest["runId"]:
            raise ValueError("unexpected case/run")
        if result["status"] not in ("PASS", "FAIL", "ERROR", "BLOCKED"):
            raise ValueError("result must be terminal")
        path = self.directory / "results" / (case + ".json")
        if path.exists():
            raise ValueError("case already recorded; do not overwrite or choose best attempt")
        atomic_json(path, dict(result, manifestSha256=self.identity))
        self.summary()

    def summary(self):
        counts = dict.fromkeys(("PASS", "FAIL", "ERROR", "BLOCKED", "PENDING"), 0)
        oracle_success = turn_completed = 0
        for case in self.ids:
            path = self.directory / "results" / (case + ".json")
            if not path.exists():
                counts["PENDING"] += 1
                continue
            result = json.loads(path.read_text())
            if result.get("manifestSha256") != self.identity or result.get("caseId") != case:
                raise ValueError("corrupt result identity")
            counts[result["status"]] += 1
            oracle_success += result.get("oracle_success") is True
            turn_completed += result.get("turn_completed") is True
        denominator = len(self.ids)
        summary = dict(manifestSha256=self.identity, denominator=denominator, counts=counts,
                       oracle_success=oracle_success, turn_completed=turn_completed,
                       valid_coverage=(counts["PASS"] + counts["FAIL"]) / denominator,
                       status="complete" if not (counts["PENDING"] + counts["ERROR"] + counts["BLOCKED"])
                       else "incomplete")
        atomic_json(self.directory / "checkpoint.json", summary)
        return summary


def execute_case(expected, initialize, oracle, agent, cleanup):
    """Execute one attempt with caller-supplied official task and Helix adapters.

    Always try teardown, even after partial initialization. Never retry a task or
    reuse an interrupted environment implicitly. The agent returns (evidence,
    instrumentation_ok); caller must validate exact JUnit method execution.
    """
    result = dict(expected, status="ERROR", oracle_success=None, turn_completed=False)
    try:
        initialize()
        initial = oracle()
        if not valid_score(initial):
            result["error"] = "missing or invalid initial oracle; agent not invoked"
        elif initial == 1.0 and not expected.get("allowsInitiallySatisfied", False):
            result["error"] = "oracle satisfied before agent execution"
        else:
            evidence, instrumentation_ok = agent()
            result = classify(expected, evidence, initial, oracle(), instrumentation_ok)
    except Exception as error:  # Preserve fixture failures; never synthesize success.
        result["error"] = bounded_error(error)
    finally:
        try:
            cleanup()
        except Exception as error:
            result["cleanup_error"] = bounded_error(error)
            result["status"] = "ERROR"
    return result

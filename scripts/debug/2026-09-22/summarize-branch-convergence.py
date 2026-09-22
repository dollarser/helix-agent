#!/usr/bin/env python3
"""Recheck a complete branch-convergence matrix from preserved, owned-runner evidence.

Read-only: emits JSON on stdout and exits nonzero for missing/failed/inconsistent evidence.
No adb, emulator, build or test execution. APK hashes refer to the copied executed artifacts;
they do not independently prove source-to-APK provenance or physical-device acceptance.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from acceptance_reports import MAX_FILE_BYTES, require, safe_load_json, unique_object_hook
from owned_acceptance import collect_owned, sha256, test_records


def load_runner():
    path = ROOT / "scripts/debug/2026-09-22/accept-branch-convergence.py"
    spec = importlib.util.spec_from_file_location("branch_convergence_summary_plan", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.runner


def plan(runner):
    result = []
    for flavor in ("consumer", "developer"):
        for api in (29, 36):
            for phase, recovery, selectors in runner.phases("216", None):
                methods, sources = runner.matrix.methods_for(selectors, flavor)
                result.append((f"{flavor}-api{api}-{phase}", flavor, api, recovery, methods, sources))
    methods, sources = runner.storage_methods()
    for api in (29, 36):
        result.append((f"storage-api{api}", "storage", api, None, methods, sources))
    require(len(result) == 30, "Integration plan is no longer the expected 30 batches")
    return result


def bounded_text(path):
    require(path.stat().st_size <= MAX_FILE_BYTES, f"Evidence exceeds bound: {path.name}")
    return path.read_text()


def recovery_evidence(directory, report, recovery, runner):
    contract = runner.RECOVERY[recovery]
    normal = safe_load_json(directory / "normal-process.json", directory)
    require(report.get("normalProcess") == normal, "Report/normal-process mismatch")
    before, after = normal.get("beforePid"), normal.get("afterPid")
    require(type(before) is int and type(after) is int and before > 0 and after > 0 and before != after,
            "Missing positive, changed normal-process PIDs")
    require(normal.get("normalActivity") is True, "Normal Activity was not verified")
    records = test_records(bounded_text(directory / "verify-logcat.txt"))
    require(set(records) == {contract["verify"]}, "Unexpected/missing recovery verification method")
    require(all(row.get("status") == "passed" and row.get("finished") for row in records.values()),
            "Recovery verification did not finish successfully")
    require(report.get("verifyMethods") == records, "Stored verifyMethods differs from raw method events")
    output = bounded_text(directory / "verify-instrumentation.txt")
    require(re.findall(r"^OK \((\d+) tests?\)", output, re.M) == ["1"], "Missing verification OK (1 test)")
    require(not any(marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")),
            "Verification instrumentation failed")
    if recovery in runner.PROCESS_RECOVERY_SCENARIOS:
        scenario = contract["scenario"]
        require(normal.get("scenario") == scenario and normal.get("sigkill") == 9,
                "Missing matching scenario/SIGKILL evidence")
        room, server = normal.get("room", {}), normal.get("server", {})
        require(room == safe_load_json(directory / "room-verified.json", directory), "Room evidence mismatch")
        require(room.get("turnState") == "INTERRUPTED" and room.get("secondRecoveryInterrupted") == 0,
                "Recovery state/idempotence failed")
        chats = 0 if scenario == "appended" else 1
        require(server.get("chatCount") == chats, "Unexpected model request count")
        if chats:
            requests = server.get("requests", [])
            require(server.get("heldCount") == 1 and server.get("disconnectedCount") == 1 and len(requests) == 1,
                    "Missing held-request disconnection evidence")
            require(requests[0].get("hasActiveInput") is True and requests[0].get("hasQueuedInput") is False,
                    "Queued input appeared in the wrong request")
        breakpoint = normal.get("breakpoint")
        if scenario in ("appended", "cancelling"):
            require(isinstance(breakpoint, dict) and breakpoint.get("hit") is True, "Missing production breakpoint")
            require(sha256(directory / f"jdb-{scenario}.txt") == breakpoint.get("transcriptSha256"),
                    "Breakpoint transcript checksum mismatch")
        else:
            require(breakpoint is None, "Unexpected breakpoint")
    return {"beforePid": before, "afterPid": after, "verifyMethod": contract["verify"],
            "sigkill": normal.get("sigkill"), "scenario": normal.get("scenario", recovery)}


def inspect_batch(base, item, recorded, runner):
    label, flavor, api, recovery, methods, sources = item
    require(recorded.get("exit") == 0 and type(recorded.get("exit")) is int,
            "Batch runner did not exit successfully")
    expected = safe_load_json(base / f"{label}-expected.json", base)
    require(expected.get("methods") == methods, "Selected methods differ from current integration plan")
    require(expected.get("sources") == sources, "Selected test-source fingerprints differ from current source")
    directory = base / label
    saved = safe_load_json(base / f"{label}-report.json", base)
    actual = collect_owned(directory, methods)
    require(actual["verdict"] == "DEVICE_BATCH_PASS", "Underlying batch did not pass")
    for key, value in actual.items():
        require(saved.get(key) == value, f"Stored report differs from raw evidence: {key}")
    require(recorded.get("verdict") == actual["verdict"] and recorded.get("counts") == actual["counts"],
            "batches.json differs from verified report")
    owner = actual["device_lifecycle"]["owner"]
    require(owner.get("avd") == f"Helix191_API{api}", "Wrong owned AVD/API")
    result = {"batch": label, "flavor": flavor, "api": api, "counts": actual["counts"],
              "owner": owner, "closed": actual["device_lifecycle"]["closed"], "artifacts": actual["artifacts"]}
    if recovery:
        result["recovery"] = recovery_evidence(directory, saved, recovery, runner)
    return result


def summarize(base):
    runner = load_runner()
    expected = plan(runner)
    errors, verified = [], []
    try:
        batches = json.loads(bounded_text(base / "batches.json"), object_pairs_hook=unique_object_hook,
                             parse_constant=lambda value: require(False, f"Invalid JSON constant: {value}"))
        require(isinstance(batches, list), "batches.json must be an array")
        require(all(isinstance(row, dict) and isinstance(row.get("batch"), str) for row in batches),
                "Invalid batch outcome")
        by_label = {row["batch"]: row for row in batches}
        require(len(by_label) == len(batches), "Duplicate batch labels")
        require(set(by_label) <= {item[0] for item in expected}, "Unexpected batch labels")
    except (OSError, ValueError, TypeError, KeyError) as error:
        errors.append(f"batches.json: {error}")
        by_label = {}
    for item in expected:
        label = item[0]
        try:
            require(label in by_label, "Missing batch outcome")
            verified.append(inspect_batch(base, item, by_label[label], runner))
        except (OSError, ValueError, TypeError, KeyError) as error:
            errors.append(f"{label}: {error}")
    artifacts = {}
    owners = set()
    for batch in verified:
        identity = (batch["owner"]["pid"], batch["owner"]["serial"], batch["owner"].get("started"))
        if identity in owners:
            errors.append(f"{batch['batch']}: reused emulator ownership identity")
        owners.add(identity)
        flavor = batch["flavor"]
        if flavor in artifacts and artifacts[flavor] != batch["artifacts"]:
            errors.append(f"{batch['batch']}: APK hashes changed within {flavor} matrix")
        artifacts[flavor] = batch["artifacts"]
    recoveries = [row["recovery"] for row in verified if "recovery" in row]
    sigkills = sum(row.get("sigkill") == 9 for row in recoveries)
    if len(recoveries) != 24 or sigkills != 16:
        errors.append(f"Recovery coverage incomplete: {len(recoveries)}/24 PID changes; {sigkills}/16 SIGKILL")
    return {"schemaVersion": 1, "verdict": "PASS" if not errors else "INCOMPLETE_OR_FAILED",
            "scope": "30 owned emulator batches only; not physical-device or source-to-APK provenance proof",
            "expectedBatches": len(expected), "verifiedBatches": len(verified),
            "instrumentationPassed": sum(row["counts"]["passed"] for row in verified),
            "recoveryVerificationPassed": len(recoveries), "changedNormalProcessPids": len(recoveries),
            "sigkillRecoveries": sigkills, "closedOwnedEmulators": len(owners),
            "apkSha256": artifacts, "batches": verified, "errors": errors}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix", type=Path, help="Existing complete matrix directory")
    args = parser.parse_args()
    try:
        report = summarize(args.matrix.resolve())
    except (OSError, ValueError, TypeError, KeyError) as error:
        report = {"verdict": "INCOMPLETE_OR_FAILED", "errors": [str(error)]}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["verdict"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())

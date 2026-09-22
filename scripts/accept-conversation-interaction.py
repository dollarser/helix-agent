#!/usr/bin/env python3
"""Run HXA-214/HXA-215/HXA-216 conversation acceptance on owned API/flavor instances.

Invoke under with-host-slot after debug app/test APK assembly. Each regression or
normal-process recovery phase gets its own emulator process and output directory.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from owned_acceptance import collect_owned, test_records

spec = importlib.util.spec_from_file_location(
    "acceptance_matrix",
    ROOT / "scripts/run-acceptance-matrix.py",
)
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

SCOPE_CLASSES = {
    "214": [
        "com.helix.app.chat.ChatSubmissionReceiptDeviceTest",
        "com.helix.app.ui.ConversationReceiptRaceDeviceTest",
        "com.helix.app.chat.ConversationStopConsistencyDeviceTest",
        "com.helix.app.chat.TurnCancellationRaceDeviceTest",
        "com.helix.app.chat.ConversationDraftRecoveryDeviceTest",
        "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest",
        "com.helix.app.chat.AttachmentE2eDeviceTest",
        "com.helix.app.ui.ChatStopProgressDeviceTest",
        "com.helix.app.ui.ConversationComposerDeviceTest",
        "com.helix.app.ui.TasksDashboardDeviceTest",
        "com.helix.app.chat.GoalRunCoordinatorDeviceTest",
        "com.helix.app.chat.GoalUsageReservationsDeviceTest",
        "com.helix.app.chat.GoalTurnBindingDeviceTest",
        "com.helix.app.ApprovalFlowDeviceTest",
    ],
    "215": [
        "com.helix.app.chat.MessageRevisionDeviceTest",
        "com.helix.app.ui.MessageEditResendFlowDeviceTest",
        "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest",
        "com.helix.app.chat.ConversationDraftRecoveryDeviceTest",
        "com.helix.app.chat.SessionForkDeviceTest",
        "com.helix.app.ui.SessionForkFlowDeviceTest",
        "com.helix.app.chat.ContextHistoryDeviceTest",
        "com.helix.app.chat.ContextCompactionDeviceTest",
        "com.helix.app.chat.LongTurnCompactionDeviceTest",
        "com.helix.app.ui.ChatCompactionFlowDeviceTest",
        "com.helix.app.ui.ChatStopProgressDeviceTest",
        "com.helix.app.chat.GoalRunCoordinatorDeviceTest",
        "com.helix.app.chat.GoalUsageReservationsDeviceTest",
        "com.helix.app.chat.GoalTurnBindingDeviceTest",
    ],
    "216": [
        "com.helix.app.chat.GoalInputSchedulingDeviceTest",
        "com.helix.app.chat.SessionInputAdmissionFailureDeviceTest",
        "com.helix.app.chat.SessionInputApprovalDeviceTest",
        "com.helix.app.chat.SessionInputProtocolDeviceTest",
        "com.helix.app.chat.SessionInputQueueDeviceTest",
        "com.helix.app.chat.SessionInputRecoveryDeviceTest",
        "com.helix.app.chat.TurnSteeringBoundaryDeviceTest",
        "com.helix.app.ui.SessionInputDeliveryDeviceTest",
        "com.helix.app.ui.ConversationComposerDeviceTest",
        "com.helix.app.chat.GoalContinuationDeviceTest",
        "com.helix.app.chat.GoalRunCoordinatorDeviceTest",
        "com.helix.app.chat.GoalUsageReservationsDeviceTest",
        "com.helix.app.chat.GoalTurnBindingDeviceTest",
        "com.helix.app.chat.ContextCompactionDeviceTest",
        "com.helix.app.chat.LongTurnCompactionDeviceTest",
        "com.helix.app.ui.ChatCompactionFlowDeviceTest",
        "com.helix.app.chat.ConversationStopConsistencyDeviceTest",
        "com.helix.app.chat.TurnCancellationRaceDeviceTest",
        "com.helix.app.ui.ChatStopProgressDeviceTest",
        "com.helix.app.ApprovalFlowDeviceTest",
    ],
}
SCOPE_CLASSES["both"] = list(
    dict.fromkeys(
        SCOPE_CLASSES["214"]
        + SCOPE_CLASSES["215"]
        + ["com.helix.app.ui.MarketplaceSectionUiTest"]
    )
)

RECOVERY = {
    "214": {
        "seed": "com.helix.app.chat.ComposerProcessRecoveryDeviceTest#seedComposerRecovery",
        "verify": "com.helix.app.chat.ComposerProcessRecoveryDeviceTest#verifyComposerRecovery",
        "after": "scripts/debug/2026-09-22/composer-process-after.py",
        "package_env": "HXA214_PACKAGE",
    },
    "215": {
        "seed": "com.helix.app.ui.MessageEditRecoveryDeviceTest#seedRevisionRecovery",
        "verify": "com.helix.app.ui.MessageEditRecoveryDeviceTest#verifyRevisionRecovery",
        "after": "scripts/debug/2026-09-22/revision-process-after.py",
        "package_env": "HXA215_PACKAGE",
    },
}

PROCESS_RECOVERY_SCENARIOS = {
    "216-pending": ("pending", "seedPendingProcessRecovery", "verifyPendingProcessRecovery"),
    "216-appended": ("appended", "seedAppendedProcessRecovery", "verifyAppendedProcessRecovery"),
    "216-http-in-flight": (
        "http_in_flight",
        "seedHttpInFlightProcessRecovery",
        "verifyHttpInFlightProcessRecovery",
    ),
    "216-cancelling": ("cancelling", "seedCancellingProcessRecovery", "verifyCancellingProcessRecovery"),
}
for recovery_scope, (scenario, seed_method, verify_method) in PROCESS_RECOVERY_SCENARIOS.items():
    RECOVERY[recovery_scope] = {
        "seed": "com.helix.app.chat.SessionInputProcessRecoveryDeviceTest#" + seed_method,
        "verify": "com.helix.app.chat.SessionInputProcessRecoveryDeviceTest#" + verify_method,
        "after": "scripts/debug/2026-09-22/input-process-after.py",
        "package_env": "HXA216_PACKAGE",
        "scenario": scenario,
    }
PROCESS_RECOVERY_SCOPE_BY_SCENARIO = {
    values[0]: recovery_scope for recovery_scope, values in PROCESS_RECOVERY_SCENARIOS.items()
}

STORAGE_216 = [
    "com.helix.core.storage.SessionInputStorageDeviceTest",
    "com.helix.core.storage.SessionInputMigrationDeviceTest",
]


def phases(scope, only, recovery_scenario=None):
    selected = []
    if only in (None, "regression"):
        selected.append(("regression", None, SCOPE_CLASSES[scope]))
    if only in (None, "recovery"):
        if scope == "both":
            recovery_scopes = ("214", "215")
        elif scope == "216":
            recovery_scopes = (
                (PROCESS_RECOVERY_SCOPE_BY_SCENARIO[recovery_scenario],)
                if recovery_scenario is not None
                else tuple(PROCESS_RECOVERY_SCENARIOS)
            )
        else:
            recovery_scopes = (scope,)
        for recovery_scope in recovery_scopes:
            label = "recovery" if scope not in ("both", "216") else f"{recovery_scope}-recovery"
            selected.append((label, recovery_scope, [RECOVERY[recovery_scope]["seed"]]))
    return selected


def storage_methods():
    expected = []
    sources = {}
    for class_name in STORAGE_216:
        source = ROOT / ("core/storage/src/androidTest/kotlin/" + class_name.replace(".", "/") + ".kt")
        methods = re.findall(r"@Test\b(?:(?!@Test\b).)*?\bfun\s+(\w+)\s*\(", source.read_text(), re.S)
        if not methods:
            raise ValueError("No storage tests discovered in " + str(source))
        expected.extend(class_name + "#" + method for method in methods)
        sources[str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
    return expected, sources


def start_process_server(base_output, label):
    ready = base_output / f"{label}-loopback-ready.json"
    events = base_output / f"{label}-loopback-events.jsonl"
    log_path = base_output / f"{label}-loopback.log"
    log = log_path.open("w")
    process = subprocess.Popen(
        [
            sys.executable,
            "scripts/debug/2026-09-22/input-process-loopback.py",
            "--ready-file",
            str(ready),
            "--events",
            str(events),
        ],
        cwd=ROOT,
        stdout=log,
        stderr=subprocess.STDOUT,
    )
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process.poll() is not None:
            log.close()
            raise RuntimeError("Input process loopback exited before readiness")
        if ready.is_file():
            return process, log, json.loads(ready.read_text()), events, log_path
        time.sleep(0.1)
    process.terminate()
    process.wait(timeout=5)
    log.close()
    raise TimeoutError("Input process loopback did not become ready")


def stop_process_server(process, log):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=tuple(SCOPE_CLASSES), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api", type=int, choices=(29, 36), action="append")
    parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
    parser.add_argument("--only", choices=("regression", "recovery", "storage"))
    parser.add_argument("--recovery-scenario", choices=tuple(PROCESS_RECOVERY_SCOPE_BY_SCENARIO))
    parser.add_argument("--first-port", type=int, default=5700)
    args = parser.parse_args()

    if args.only == "storage" and args.scope != "216":
        parser.error("--only storage is available only for --scope 216")
    if args.recovery_scenario is not None and (args.scope != "216" or args.only != "recovery"):
        parser.error("--recovery-scenario requires --scope 216 --only recovery")

    flavors = args.flavor or ("consumer", "developer")
    apis = args.api or (29, 36)
    app_batches = len(phases(args.scope, args.only, args.recovery_scenario)) * len(flavors) * len(apis)
    storage_batches = len(apis) if args.scope == "216" and args.only in (None, "storage") else 0
    batch_count = app_batches + storage_batches
    last_port = args.first_port + max(0, batch_count - 1) * 2
    if args.first_port % 2 or args.first_port < 5554 or last_port > 5750:
        parser.error(
            f"owned emulator ports must be even and remain in 5554..5750; "
            f"{batch_count} batches require {args.first_port}..{last_port}"
        )

    args.output.mkdir(parents=True, exist_ok=False)
    outcomes = []
    port = args.first_port
    for flavor in flavors:
        for api in apis:
            for phase, recovery_scope, selected in phases(args.scope, args.only, args.recovery_scenario):
                label = f"{flavor}-api{api}-{phase}"
                expected, sources = matrix.methods_for(selected, flavor)
                (args.output / f"{label}-expected.json").write_text(
                    json.dumps({"methods": expected, "sources": sources}, indent=2)
                )
                target = args.output / label
                suffix = ".developer" if flavor == "developer" else ""
                package = f"com.helix.agent{suffix}"
                command = [
                    sys.executable,
                    "scripts/run-owned-acceptance-emulator.py",
                    "--avd",
                    f"Helix191_API{api}",
                    "--port",
                    str(port),
                    "--memory-mb",
                    "4096",
                    "--cores",
                    "4",
                    "--apk",
                    f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
                    "--test-apk",
                    f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
                    "--runner",
                    f"{package}.test/com.helix.app.HelixAndroidJUnitRunner",
                    "--classes",
                    ",".join(selected),
                    "--output",
                    str(target),
                    "--timeout",
                    "1200" if recovery_scope is None else "900",
                ]
                environment = os.environ.copy()
                server = None
                if recovery_scope is not None:
                    recovery = RECOVERY[recovery_scope]
                    command += ["--after-script", recovery["after"]]
                    environment[recovery["package_env"]] = package
                    if "scenario" in recovery:
                        server = start_process_server(args.output, label)
                        _, _, server_ready, _, _ = server
                        server_port = server_ready["port"]
                        command += [
                            "--reverse-port",
                            str(server_port),
                            "--instrument-arg",
                            f"inputProcessPort={server_port}",
                        ]
                        environment["HXA216_SCENARIO"] = recovery["scenario"]
                        environment["HXA216_SERVER_PORT"] = str(server_port)
                        environment["HXA216_FLAVOR"] = flavor
                print(
                    f"Starting {label}: {len(expected)} methods across {len(selected)} selectors",
                    flush=True,
                )
                try:
                    with (args.output / f"{label}.log").open("w") as log:
                        result = subprocess.run(
                            command,
                            cwd=ROOT,
                            env=environment,
                            stdout=log,
                            stderr=subprocess.STDOUT,
                        )
                finally:
                    if server is not None:
                        process, server_log, _, events, server_log_path = server
                        stop_process_server(process, server_log)
                        if target.is_dir():
                            shutil.copyfile(events, target / "server-events.jsonl")
                            shutil.copyfile(server_log_path, target / "server.log")
                report = collect_owned(target, expected) if result.returncode == 0 else {"exit": result.returncode}
                if recovery_scope is not None and result.returncode == 0:
                    normal = json.loads((target / "normal-process.json").read_text())
                    records = test_records((target / "verify-logcat.txt").read_text())
                    verified = records.get(RECOVERY[recovery_scope]["verify"], {})
                    if normal.get("beforePid") == normal.get("afterPid"):
                        raise RuntimeError(f"{label}: normal MainActivity PID did not change")
                    if normal.get("normalActivity") is not True:
                        raise RuntimeError(f"{label}: normal MainActivity evidence is absent")
                    if verified.get("status") != "passed":
                        raise RuntimeError(f"{label}: Room verification did not pass")
                    if recovery_scope in PROCESS_RECOVERY_SCENARIOS:
                        expected_chats = 0 if recovery["scenario"] == "appended" else 1
                        server_evidence = normal.get("server", {})
                        room_evidence = normal.get("room", {})
                        if normal.get("scenario") != recovery["scenario"] or normal.get("sigkill") != 9:
                            raise RuntimeError(f"{label}: wrong scenario or missing SIGKILL evidence")
                        if server_evidence.get("chatCount") != expected_chats:
                            raise RuntimeError(f"{label}: wrong model HTTP request count")
                        if room_evidence.get("turnState") != "INTERRUPTED":
                            raise RuntimeError(f"{label}: Room turn was not recovered as INTERRUPTED")
                        if room_evidence.get("secondRecoveryInterrupted") != 0:
                            raise RuntimeError(f"{label}: recovery was not idempotent")
                        if expected_chats == 1:
                            requests = server_evidence.get("requests", [])
                            if (
                                server_evidence.get("heldCount") != 1
                                or server_evidence.get("disconnectedCount") != 1
                                or len(requests) != 1
                                or requests[0].get("hasActiveInput") is not True
                                or requests[0].get("hasQueuedInput") is not False
                            ):
                                raise RuntimeError(f"{label}: incomplete model boundary evidence")
                        if recovery["scenario"] in ("appended", "cancelling"):
                            breakpoint = normal.get("breakpoint") or {}
                            if breakpoint.get("hit") is not True or not breakpoint.get("transcriptSha256"):
                                raise RuntimeError(f"{label}: production JDWP breakpoint was not proven")
                        elif normal.get("breakpoint") is not None:
                            raise RuntimeError(f"{label}: unexpected breakpoint evidence")
                    report["normalProcess"] = normal
                    report["verifyMethods"] = records
                (args.output / f"{label}-report.json").write_text(json.dumps(report, indent=2))
                outcomes.append(
                    {
                        "batch": label,
                        "exit": result.returncode,
                        "verdict": report.get("verdict"),
                        "counts": report.get("counts"),
                    }
                )
                (args.output / "batches.json").write_text(json.dumps(outcomes, indent=2))
                print(outcomes[-1], flush=True)
                if result.returncode or report.get("verdict") != "DEVICE_BATCH_PASS":
                    return 1
                port += 2
    if args.scope == "216" and args.only in (None, "storage"):
        expected, sources = storage_methods()
        for api in apis:
            label = f"storage-api{api}"
            (args.output / f"{label}-expected.json").write_text(
                json.dumps({"methods": expected, "sources": sources}, indent=2)
            )
            target = args.output / label
            storage_apk = "core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk"
            command = [
                sys.executable,
                "scripts/run-owned-acceptance-emulator.py",
                "--avd",
                f"Helix191_API{api}",
                "--port",
                str(port),
                "--memory-mb",
                "4096",
                "--cores",
                "4",
                "--apk",
                storage_apk,
                "--test-apk",
                storage_apk,
                "--runner",
                "com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner",
                "--classes",
                ",".join(STORAGE_216),
                "--output",
                str(target),
                "--timeout",
                "900",
            ]
            with (args.output / f"{label}.log").open("w") as log:
                result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            report = collect_owned(target, expected) if result.returncode == 0 else {"exit": result.returncode}
            (args.output / f"{label}-report.json").write_text(json.dumps(report, indent=2))
            outcomes.append(
                {
                    "batch": label,
                    "exit": result.returncode,
                    "verdict": report.get("verdict"),
                    "counts": report.get("counts"),
                }
            )
            (args.output / "batches.json").write_text(json.dumps(outcomes, indent=2))
            print(outcomes[-1], flush=True)
            if result.returncode or report.get("verdict") != "DEVICE_BATCH_PASS":
                return 1
            port += 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

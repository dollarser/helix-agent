#!/usr/bin/env python3
"""Run HXA-214/HXA-215 conversation acceptance on owned API/flavor instances.

Invoke under with-host-slot after debug app/test APK assembly. Each regression or
normal-process recovery phase gets its own emulator process and output directory.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from owned_acceptance import collect_owned, test_records

spec = importlib.util.spec_from_file_location(
    "acceptance_matrix",
    ROOT / "scripts/debug/2026-09-21/run-acceptance-matrix.py",
)
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

SCOPE_CLASSES = {
    "214": [
        "com.helix.app.chat.ChatSubmissionReceiptDeviceTest",
        "com.helix.app.chat.ConversationStopConsistencyDeviceTest",
        "com.helix.app.chat.ConversationDraftRecoveryDeviceTest",
        "com.helix.app.chat.ChatServiceAttachmentRetryDeviceTest",
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


def phases(scope, only):
    selected = []
    if only in (None, "regression"):
        selected.append(("regression", None, SCOPE_CLASSES[scope]))
    if only in (None, "recovery"):
        recovery_scopes = ("214", "215") if scope == "both" else (scope,)
        for recovery_scope in recovery_scopes:
            label = "recovery" if scope != "both" else f"{recovery_scope}-recovery"
            selected.append((label, recovery_scope, [RECOVERY[recovery_scope]["seed"]]))
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=tuple(SCOPE_CLASSES), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api", type=int, choices=(29, 36), action="append")
    parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
    parser.add_argument("--only", choices=("regression", "recovery"))
    parser.add_argument("--first-port", type=int, default=5700)
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=False)
    outcomes = []
    port = args.first_port
    for flavor in args.flavor or ("consumer", "developer"):
        for api in args.api or (29, 36):
            for phase, recovery_scope, selected in phases(args.scope, args.only):
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
                    "scripts/debug/2026-09-18/run-owned-emulator-207.py",
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
                if recovery_scope is not None:
                    recovery = RECOVERY[recovery_scope]
                    command += ["--after-script", recovery["after"]]
                    environment[recovery["package_env"]] = package
                print(
                    f"Starting {label}: {len(expected)} methods across {len(selected)} selectors",
                    flush=True,
                )
                with (args.output / f"{label}.log").open("w") as log:
                    result = subprocess.run(
                        command,
                        cwd=ROOT,
                        env=environment,
                        stdout=log,
                        stderr=subprocess.STDOUT,
                    )
                report = collect_owned(target, expected) if result.returncode == 0 else {"exit": result.returncode}
                if recovery_scope is not None and result.returncode == 0:
                    normal = json.loads((target / "normal-process.json").read_text())
                    records = test_records((target / "verify-logcat.txt").read_text())
                    verified = records.get(RECOVERY[recovery_scope]["verify"], {})
                    assert normal["beforePid"] != normal["afterPid"]
                    assert normal["normalActivity"] is True
                    assert verified.get("status") == "passed"
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
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

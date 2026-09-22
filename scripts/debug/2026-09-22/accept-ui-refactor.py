#!/usr/bin/env python3
"""Run conversation presentation and interaction regression on four owned API/flavor instances.

Invoke under with-host-slot after debug app/test APK assembly. No account or external network profile.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from owned_acceptance import collect_owned

spec = importlib.util.spec_from_file_location("acceptance_matrix", ROOT / "scripts/debug/2026-09-21/run-acceptance-matrix.py")
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api", type=int, choices=(29, 36), action="append")
    parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
    parser.add_argument("--capture", action="store_true", help="Capture synthetic Chinese UI fixtures")
    parser.add_argument("--first-port", type=int, default=5740)
    parser.add_argument("--visual-only", action="store_true", help="Run the four production navigation/sheet checks for screenshot review")
    args = parser.parse_args()
    batches = len(args.api or (29, 36)) * len(args.flavor or ("consumer", "developer"))
    if args.first_port % 2 or not 5554 <= args.first_port <= 5750 - 2 * (batches - 1):
        parser.error("all console ports must be even and within the owned runner's 5554..5750 range")
    args.output.mkdir(parents=True, exist_ok=False)
    classes = ["com.helix.app.ui." + name for name in (
        "ConversationHeaderDeviceTest", "ConversationComposerDeviceTest",
        "ConversationTopBarDeviceTest", "ToolTimelineLayoutDeviceTest",
        "ModeLayoutDeviceTest", "ContextWindowDeviceTest", "SessionModelDeviceTest",
        "NavigationLayoutDeviceTest", "GroupedNavigationDeviceTest", "TaskLedgerProgressDeviceTest",
        "BackgroundTaskFlowDeviceTest", "ApprovalLayoutDeviceTest",
        "ChatCompactionFlowDeviceTest", "ChatStopProgressDeviceTest",
        "SessionForkFlowDeviceTest",
    )]
    if args.visual_only:
        classes = ["com.helix.app.ui.ConversationTopBarDeviceTest"]
    outcomes = []
    port = args.first_port
    for flavor in args.flavor or ("consumer", "developer"):
        for api in args.api or (29, 36):
            label = f"{flavor}-api{api}"
            expected, sources = matrix.methods_for(classes, flavor)
            (args.output / f"{label}-expected.json").write_text(json.dumps({"methods": expected, "sources": sources}, indent=2))
            target = args.output / label
            suffix = ".developer" if flavor == "developer" else ""
            command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
                       "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
                       "--apk", f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
                       "--test-apk", f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
                       "--runner", f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner",
                       "--classes", ",".join(classes), "--output", str(target), "--timeout", "900"]
            print(f"Starting {label}: {len(expected)} methods", flush=True)
            if args.capture:
                command += ["--instrument-arg", "helix.layout.capture=true",
                            "--instrument-arg", "helix.test.language=zh-CN",
                            "--after-script", "scripts/debug/2026-09-22/capture-ui-refactor.py"]
            with (args.output / f"{label}.log").open("w") as log:
                result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            report = collect_owned(target, expected) if result.returncode == 0 else {"exit": result.returncode}
            (args.output / f"{label}-report.json").write_text(json.dumps(report, indent=2))
            outcomes.append({"batch": label, "exit": result.returncode,
                             "verdict": report.get("verdict"), "counts": report.get("counts")})
            (args.output / "batches.json").write_text(json.dumps(outcomes, indent=2))
            print(outcomes[-1], flush=True)
            port += 2
            if result.returncode or report.get("verdict") != "DEVICE_BATCH_PASS":
                return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

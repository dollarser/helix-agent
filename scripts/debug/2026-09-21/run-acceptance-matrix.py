#!/usr/bin/env python3
"""199/206 device batches. Invoke under with-host-slot after building the selected APKs.

Reuse the owned runner; fix expected methods from current source before launching.
Batch evidence is deliberately separate from whole-product/physical acceptance.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from owned_acceptance import collect_owned, sha256

PRODUCT = {
    "file-task": (["ui.ProductFileJourneyDeviceTest"], None),
    "delivery": (["ArtifactDeliveryDeviceTest", "CommandExecutionDetailsDeviceTest", "ui.GitStatusDeviceTest",
                  "ui.GitReadOnlyBoundaryDeviceTest"], None),
    "authorization": (["SessionPermissionDeviceTest", "SessionPermissionRecoveryDeviceTest",
                       "PermissionAtomicityDeviceTest"], "SessionPermissionRecoveryDeviceTest"),
    "recovery": (["RecoveryJourneyDeviceTest", "ui.ChatStopProgressDeviceTest", "chat.ToolResultReadDeviceTest"],
                 "RecoveryJourneyDeviceTest"),
    "tasks": (["TaskJourneyDeviceTest"], "TaskJourneyDeviceTest"),
    "plan": (["plan.PlanExecuteCloseLoopDeviceTest", "plan.PlanExecutionAcceptanceDeviceTest",
              "plan.PlanAuthorizationLinkageDeviceTest", "plan.PlanExecutionRecoveryDeviceTest"],
             "plan.PlanExecutionRecoveryDeviceTest"),
    "extensions": (["ExtensionJourneyDeviceTest"], None),
    "extensions-restart": (["ExtensionJourneyDeviceTest#seedExtensionJourneyScope"], None),
    "readiness": (["ui.CapabilityReadinessDeviceTest"], "ui.CapabilityReadinessDeviceTest"),
    "search": (["ui.SessionSearchDeviceTest"], "ui.SessionSearchDeviceTest"),
    "theme-light": (["ui.HelixThemeDeviceTest", "ui.ThemeDialogDeviceTest"], "ui.HelixThemeDeviceTest"),
    "theme-dark": (["ui.HelixThemeDeviceTest", "ui.ThemeDialogDeviceTest"], "ui.HelixThemeDeviceTest"),
    "export": (["export.SessionExportRecoveryDeviceTest", "export.SessionExportJourneyDeviceTest",
                "ui.SessionExportUiDeviceTest", "ui.SessionExportPickerDeviceTest"],
               "export.SessionExportRecoveryDeviceTest"),
}


def methods_for(selectors, flavor):
    methods, sources = [], {}
    for selector in selectors:
        cls, _, method = selector.partition("#")
        candidates = [ROOT / "app/src" / source_set / "kotlin" / (cls.replace(".", "/") + ".kt")
                      for source_set in ("androidTest", "androidTest" + flavor.title())]
        source = next((path for path in candidates if path.is_file()), None)
        if source is None:
            raise ValueError(f"Missing source for {selector}")
        text = source.read_text()
        text = re.sub(r"^\s*//.*$", "", text, flags=re.M)
        found = re.findall(r"@Test\b(?:(?!@Test\b).)*?\bfun\s+(\w+)\s*\(", text, re.S)
        if not found or method and method not in found:
            raise ValueError(f"No matching @Test method for {selector}")
        methods.extend(f"{cls}#{name}" for name in ([method] if method else found))
        sources[str(source.relative_to(ROOT))] = sha256(source)
    if len(set(methods)) != len(methods):
        raise ValueError("Duplicate expected methods")
    return methods, sources


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=("199", "206"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api", type=int, choices=(29, 36), action="append")
    parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
    parser.add_argument("--group", action="append")
    parser.add_argument("--first-port", type=int, default=5640)
    parser.add_argument("--start-at", help="Resume at a named flavor-api-group batch in a NEW output directory")
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--app-directory", type=Path,
                        help="Optional preserved production APK directory for same-fixture baseline comparison")
    args = parser.parse_args()
    if args.scope == "199":
        spec = importlib.util.spec_from_file_location("integrated", ROOT / "scripts/verify-integrated-runtimes.py")
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
        groups = {
            "runtime": (runtime.CASES + ["com.helix.app.proot.ProotTerminalUiDeviceTest"], None),
            "logs-jobs": (["com.helix.app.proot.ProotLogStreamDeviceTest", "com.helix.app.proot.ProotDetachedJobDeviceTest"], None),
            "dual-terminal": (["com.helix.app.proot.ProotMultiSessionDeviceTest"], None),
        }
        flavors = args.flavor or ["developer"]
        if flavors != ["developer"]:
            raise ValueError("199 Runtime instrumentation is developer-only; consumer exclusion is an APK gate")
    else:
        groups = {name: (["com.helix.app." + cls for cls in classes], "com.helix.app." + setup if setup else None)
                  for name, (classes, setup) in PRODUCT.items()}
        flavors = args.flavor or ["consumer", "developer"]
    selected = args.group or list(groups)
    if any(name not in groups for name in selected):
        raise ValueError("Unknown group")
    args.output.mkdir(parents=True, exist_ok=False)
    outcomes = []
    port = args.first_port
    waiting_for = args.start_at
    for flavor in flavors:
        for api in args.api or [29, 36]:
            for name in selected:
                label = f"{flavor}-api{api}-{name}"
                if waiting_for:
                    if label != waiting_for:
                        continue
                    waiting_for = None
                classes, recovery = groups[name]
                if name.startswith("theme-") and flavor == "developer":
                    classes = classes + ["com.helix.app.proot.SubscriptionThemeDeviceTest"]
                expected, sources = methods_for(classes, flavor)
                plan = {"methods": expected, "source_sha256": sources,
                        "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
                (args.output / f"{label}-expected.json").write_text(json.dumps(plan, indent=2) + "\n")
                if args.plan_only:
                    print(f"{label}: {len(expected)} expected methods", flush=True)
                    continue
                suffix = ".developer" if flavor == "developer" else ""
                target = args.output / label
                app = (args.app_directory / f"app-{flavor}-debug.apk" if args.app_directory else
                       Path(f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk"))
                command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
                           "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
                           "--apk", str(app),
                           "--test-apk", f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
                           "--runner", f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner",
                           "--classes", ",".join(classes), "--output", str(target), "--timeout", "1200"]
                if recovery:
                    command.extend(["--recovery-setup-class", recovery, "--instrument-arg", "recoveryPhase=verify"])
                environment = dict(os.environ)
                if name == "extensions-restart":
                    command.extend(["--instrument-arg", "extensionJourneyPhase=seed", "--after-script",
                                    "scripts/debug/2026-09-18/hxa207-restart-recover.py"])
                    environment.update(HXA207_RUNNER=f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner",
                                       HXA207_APP_PACKAGE=f"com.helix.agent{suffix}")
                if name.startswith("theme-"):
                    night = "yes" if name == "theme-dark" else "no"
                    command.extend(["--night-mode", night, "--instrument-arg", f"expectedNight={night}"])
                    command.extend(["--recovery-pid-file", "no_backup/theme-recovery-pid"])
                if name.startswith("theme-") or name == "export":
                    command.extend(["--after-script", "scripts/debug/2026-09-21/collect-product-fixtures.py"])
                    environment.update(HELIX_FIXTURE_PACKAGE=f"com.helix.agent{suffix}", HELIX_FIXTURE_GROUP=name)
                print(f"Starting {label}: {len(expected)} expected methods", flush=True)
                with (args.output / f"{label}.log").open("w") as log:
                    result = subprocess.run(command, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT)
                outcome = {"batch": label, "exit": result.returncode}
                if result.returncode == 0:
                    report = collect_owned(target, expected)
                    (args.output / f"{label}-report.json").write_text(json.dumps(report, indent=2) + "\n")
                    outcome.update(verdict=report["verdict"], counts=report["counts"])
                outcomes.append(outcome)
                (args.output / "batches.json").write_text(json.dumps(outcomes, indent=2) + "\n")
                print(outcome, flush=True)
                if result.returncode:
                    return result.returncode
                port += 2
    if waiting_for:
        raise ValueError("Requested start batch was not in the selected matrix")
    return 1 if any(outcome.get("verdict") != "DEVICE_BATCH_PASS" for outcome in outcomes) else 0


if __name__ == "__main__":
    raise SystemExit(main())

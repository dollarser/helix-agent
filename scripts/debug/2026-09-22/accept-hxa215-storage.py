#!/usr/bin/env python3
"""Run flavor-independent HXA-215 storage migrations and revision export on owned API29/36 instances.

Invoke under with-host-slot after debug app/test APK assembly. No account or external network profile.
"""
import argparse
import json
import hashlib
import re
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from owned_acceptance import collect_owned

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--api", type=int, choices=(29, 36), action="append")
    parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    classes = ["com.helix.core.storage.RoomMigrationFixtureTest", "com.helix.core.storage.SessionExportSnapshotDeviceTest"]
    outcomes = []
    port = 5740
    for flavor in args.flavor or ("consumer",):
        for api in args.api or (29, 36):
            label = f"{flavor}-api{api}"
            expected = []
            sources = {}
            for class_name in classes:
                source = ROOT / ("core/storage/src/androidTest/kotlin/" + class_name.replace(".", "/") + ".kt")
                methods = re.findall(r"@Test\b(?:(?!@Test\b).)*?\bfun\s+(\w+)\s*\(", source.read_text(), re.S)
                if not methods:
                    raise ValueError("No storage tests discovered")
                expected.extend(class_name + "#" + method for method in methods)
                sources[str(source.relative_to(ROOT))] = hashlib.sha256(source.read_bytes()).hexdigest()
            (args.output / f"{label}-expected.json").write_text(json.dumps({"methods": expected, "sources": sources}, indent=2))
            target = args.output / label
            command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
                       "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
                       "--apk", f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
                       "--test-apk", "core/storage/build/outputs/apk/androidTest/debug/storage-debug-androidTest.apk",
                       "--runner", "com.helix.core.storage.test/androidx.test.runner.AndroidJUnitRunner",
                       "--classes", ",".join(classes), "--output", str(target), "--timeout", "900"]
            print(f"Starting {label}: {len(expected)} methods", flush=True)
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

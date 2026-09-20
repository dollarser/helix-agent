#!/usr/bin/env python3
"""HXA-211: two-phase process death, real document faults and UI in fresh exclusive AVDs."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--flavor", action="append", choices=("consumer", "developer"))
    parser.add_argument("--api", action="append", type=int, choices=(29, 36))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    outcomes = []
    for flavor in args.flavor or ("consumer", "developer"):
        for api in args.api or (29, 36):
            suffix = ".developer" if flavor == "developer" else ""
            runner = f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner"
            target = f"{flavor}-api{api}"
            port = (5584 if api == 29 else 5586) + (6 if flavor == "developer" else 0)
            command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
                       "--avd", f"Helix191_API{api}", "--port", str(port),
                       "--memory-mb", "4096", "--cores", "4",
                       "--apk", f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
                       "--test-apk", f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
                       "--runner", runner, "--output", str(output / target), "--timeout", "180",
                       "--recovery-setup-class", "com.helix.app.export.SessionExportRecoveryDeviceTest",
                       "--classes", "com.helix.app.export.SessionExportRecoveryDeviceTest",
                       "--instrument-arg", "recoveryPhase=verify",
                       "--after-script", "scripts/debug/2026-09-20/session-export-follow-up.py"]
            environment = dict(os.environ, HXA211_RUNNER=runner)
            print(f"Starting {target}", flush=True)
            with (output / f"{target}.log").open("w") as log:
                result = subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT)
            outcomes.append({"target": target, "exit": result.returncode})
            (output / "summary.json").write_text(json.dumps(outcomes, indent=2))
            print(f"Finished {target}: exit={result.returncode}", flush=True)
            if result.returncode:
                return result.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

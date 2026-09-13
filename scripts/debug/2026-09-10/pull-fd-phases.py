#!/usr/bin/env python3
"""run-owned-emulator.py --after-script for the FD五臂 (DescriptorPhaseProbeDeviceTest).

The FD probe writes fd-phases-<runId>.jsonl into the APP package's external files dir
(/sdcard/Android/data/com.helix.agent/files). The owned emulator is torn down in the
launcher's finally, so this follow-up must PULL the evidence first. Arms run sequentially and
this script deletes each device copy after pulling, so normally exactly one file is present;
it is written to handle the general 1-or-more case without relying on `ls -t` ordering. For
each file: pull it, run the frozen analyzer, delete the device copy. Usage:
pull-fd-phases.py <serial> <output-dir>. stdout is captured into follow-up.txt."""
import os
import subprocess
from pathlib import Path


def run(args):
    sdk = Path(os.environ["ANDROID_HOME"])
    adb = str(sdk / "platform-tools/adb")
    serial, output = args
    out = Path(output)
    app_dir = "/sdcard/Android/data/com.helix.agent/files"

    listing = subprocess.run([adb, "-s", serial, "shell", "ls", app_dir],
                             text=True, capture_output=True, check=True).stdout
    names = [line.strip() for line in listing.splitlines()
             if line.strip().startswith("fd-phases-") and line.strip().endswith(".jsonl")]
    if not names:
        raise RuntimeError(f"no fd-phases-*.jsonl found in {app_dir} (FD probe did not write output?)")
    if len(names) > 1:
        print(f"WARNING: {len(names)} fd-phases files present (expected 1); pulling all: {names}", flush=True)
    for name in names:
        local = out / name
        subprocess.run([adb, "-s", serial, "pull", f"{app_dir}/{name}", str(local)],
                       check=True, capture_output=True)
        print(f"pulled {app_dir}/{name} -> {local}", flush=True)
        completed = subprocess.run(["python3", "scripts/debug/2026-09-10/analyze-fd-phases.py", str(local)],
                                   text=True, capture_output=True)
        print(f"--- analyze-fd-phases.py {name} (exit {completed.returncode}) ---", flush=True)
        print(completed.stdout, flush=True)
        if completed.stderr:
            print("stderr:", completed.stderr, flush=True)
        subprocess.run([adb, "-s", serial, "shell", "rm", f"{app_dir}/{name}"],
                       check=True, capture_output=True)
        print(f"deleted device copy {name}", flush=True)


if __name__ == "__main__":
    import sys
    if len(sys.argv) != 3:
        raise SystemExit("usage: pull-fd-phases.py <serial> <output-dir>")
    run(sys.argv[1:3])

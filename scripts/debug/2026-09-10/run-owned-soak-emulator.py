#!/usr/bin/env python3
"""Launch an OWNED emulator, drive run-browser-autofill-soak.py against it, and ALWAYS close it.

Forks the ownership contract of scripts/debug/2026-09-09/run-owned-emulator.py (refuse a taken
serial, verify AVD identity, force 1080x2400@420, record pid/serial/avd/bootId, kill only our own
process group in a finally block) but drives the soak RUNNER instead of a single `am instrument`.
The runner writes its own evidence (result.json / progress.json / cycles.jsonl / manifest.json /
logcat / meminfo) to <output>/runner; this wrapper writes owner.json / emulator.log / closed.json /
runner-exit.json to <output>. The runner's exit code is recorded but is NOT the success criterion --
result.json's verdict is the ground truth (see the run-index harness quirk)."""
import argparse
import json
import os
import subprocess
import signal
import socket
import time
from pathlib import Path


def run(args):
    sdk = Path(os.environ["ANDROID_HOME"])
    adb = str(sdk / "platform-tools/adb")
    serial = f"emulator-{args.port}"
    devices = subprocess.check_output([adb, "devices"], text=True)
    if any(line.split()[0] == serial for line in devices.splitlines()[1:] if line.split()):
        raise RuntimeError(f"Refusing existing device {serial}")
    if args.port % 2 or not 5554 <= args.port <= 5682:
        raise ValueError("Use an even emulator console port between 5554 and 5682")
    for port in (args.port, args.port + 1):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=False)
    command = [str(sdk / "emulator/emulator"), "-avd", args.avd, "-port", str(args.port),
               "-read-only", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim",
               "-memory", "2048", "-cores", "2", "-gpu", "swiftshader_indirect"]
    with (out / "emulator.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        boot_id = None
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Owned emulator exited during boot")
                try:
                    completed = subprocess.run(
                        [adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                        text=True, capture_output=True, timeout=10, check=True).stdout.strip()
                    if completed == "1":
                        boot_id = subprocess.run(
                            [adb, "-s", serial, "shell", "getprop", "ro.boot.bootid"],
                            text=True, capture_output=True, timeout=10).stdout.strip() or None
                        break
                except subprocess.CalledProcessError:
                    pass
                time.sleep(2)
            else:
                raise TimeoutError("Emulator boot deadline exceeded")
            if args.avd not in subprocess.run(
                    [adb, "-s", serial, "emu", "avd", "name"],
                    text=True, capture_output=True).stdout.splitlines():
                raise RuntimeError("AVD identity mismatch")
            subprocess.run([adb, "-s", serial, "shell", "wm", "size", "1080x2400"],
                           check=True, capture_output=True)
            subprocess.run([adb, "-s", serial, "shell", "wm", "density", "420"],
                           check=True, capture_output=True)
            (out / "owner.json").write_text(json.dumps(
                {"pid": process.pid, "serial": serial, "avd": args.avd, "bootId": boot_id,
                 "started": time.time()}, indent=2))
            # The runner creates <out>/runner itself (it refuses a pre-existing --output dir).
            runner = subprocess.run(
                ["python3", args.runner, "--serial", serial, "--output", str(out / "runner"),
                 "--preset", args.preset, "--autofill-mode", args.mode, "--sampler", "full",
                 "--run-id", args.run_id, "--apk", args.apk, "--no-sleep-check"])
            (out / "runner-exit.json").write_text(json.dumps(
                {"runnerExitCode": runner.returncode,
                 "note": "exit 0 == PASS only; result.json verdict is the ground truth"}))
        except BaseException as error:  # noqa: BLE001 - record then re-raise; finally still closes
            (out / "wrapper-error.txt").write_text(repr(error) + "\n" + str(error) + "\n")
            raise
        finally:
            # Terminate only our own process group; no broad adb emu kill / serial-based cleanup.
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            (out / "closed.json").write_text(json.dumps(
                {"pid": process.pid, "exit": process.returncode}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--preset", required=True)
    parser.add_argument("--mode", required=True, choices=["on", "off"])
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--runner", default="scripts/run-browser-autofill-soak.py")
    run(parser.parse_args())

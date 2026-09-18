#!/usr/bin/env python3
"""HXA-191 manual theme smoke (one exclusive emulator): switch the REAL system night mode
with `cmd uimode`, relaunch the app, and capture screenshots + window/config dumps in both
modes. Grounds the named device-test assertions (statusBarColor / windowLightStatusBar /
content luminance behaviour) before the full 4-quadrant matrix. Teardown only the owned
process group in finally."""
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

SDK = Path(os.environ["ANDROID_HOME"])
ADB = str(SDK / "platform-tools/adb")
AVD = "HelixApkUpgrade_API36_20260918"
PORT = 5700
APK = "app/build/outputs/apk/consumer/debug/app-consumer-debug.apk"
OUT = Path("build/hxa-191-theme-smoke-api36")

serial = f"emulator-{PORT}"


def run(args, timeout=120, check=True):
    proc = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    if check and proc.returncode != 0:
        raise subprocess.CalledProcessError(
            proc.returncode, args, output=proc.stdout, stderr=proc.stderr)
    return proc.stdout


def device(*argv, timeout=120, check=True):
    return run([ADB, "-s", serial, *argv], timeout, check)


def free(port):
    with socket.socket() as p:
        p.bind(("127.0.0.1", port))


def main():
    devices = run([ADB, "devices"]).splitlines()
    if any(l.split()[0] == serial for l in devices[1:] if l.split()):
        raise RuntimeError(f"Refusing existing device {serial}")
    for p in (PORT, PORT + 1):
        free(p)
    OUT.mkdir(parents=True, exist_ok=False)
    with (OUT / "emulator.log").open("w") as log:
        proc = subprocess.Popen(
            [str(SDK / "emulator/emulator"), "-avd", AVD, "-port", str(PORT), "-read-only",
             "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim", "-memory", "4096",
             "-cores", "4", "-gpu", "swiftshader_indirect"],
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (OUT / "owner.json").write_text(json.dumps({"pid": proc.pid, "serial": serial, "avd": AVD}))
        try:
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError("emulator exited during boot")
                try:
                    if device("shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
                        break
                except subprocess.CalledProcessError:
                    pass
                time.sleep(2)
            else:
                raise TimeoutError("boot deadline")
            device("shell", "wm", "size", "1080x2400")
            device("shell", "wm", "density", "420")
            device("install", "-r", APK, timeout=180)
            for mode, night in (("light", "no"), ("dark", "yes")):
                device("shell", "cmd", "uimode", "night", night)
                state = device("shell", "cmd", "uimode", "night")
                (OUT / f"{mode}-uimode.txt").write_text(state)
                device("shell", "am", "force-stop", "com.helix.agent")
                started = device(
                    "shell", "am", "start", "-W", "-n", "com.helix.agent/com.helix.app.MainActivity",
                    check=False)
                (OUT / f"{mode}-am-start.txt").write_text(started)
                print(f"[{mode}] am start rc-see-above:", started.strip().splitlines()[-3:], flush=True)
                time.sleep(6)
                device("shell", "screencap", "-p", "/sdcard/theme-smoke.png", timeout=60)
                run([ADB, "-s", serial, "pull", "/sdcard/theme-smoke.png", str(OUT / f"{mode}.png")])
                (OUT / f"{mode}-dumpsys-window.txt").write_text(
                    device("shell", "dumpsys", "window", timeout=60))
                (OUT / f"{mode}-dumpsys-activity-top.txt").write_text(
                    device("shell", "dumpsys", "activity", "top", timeout=60))
                print(f"[{mode}] uimode={state.strip()[:60]!r}", flush=True)
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=10)
            (OUT / "closed.json").write_text(json.dumps({"pid": proc.pid, "exit": proc.returncode}))


if __name__ == "__main__":
    sys.exit(main())

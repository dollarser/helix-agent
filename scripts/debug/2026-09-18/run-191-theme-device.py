#!/usr/bin/env python3
"""HXA-191 dark-theme gate driver: ONE exclusive emulator per quadrant (consumer|developer x
API29|API36) on the shared HelixApkUpgrade API image, driving the REAL system night mode
(`cmd uimode`) and font scale, then running the NAMED HelixThemeDeviceTest twice per mode —
`recoveryPhase=setup` records the process identity and kills the process; `recoveryPhase=verify`
asserts a fresh process re-derives the theme and runs the mode/rotation/destination/language
facets. The dark pass runs at a larger font scale (1.3) so the "large font" regression is covered
by the same pass. Teardown only the owned process group in finally. Output lands in the given
build/ dir (ignored).

Usage: run-191-theme-device.py <consumer|developer> <29|36> <port> <output-dir>
  e.g. run-191-theme-device.py consumer 36 5700 build/hxa-191-theme-consumer-api36
"""
import hashlib
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

SDK = Path(os.environ["ANDROID_HOME"])
ADB = str(SDK / "platform-tools/adb")
EMULATOR = str(SDK / "emulator/emulator")


def adb_run(args, timeout=120):
    return subprocess.run(args, text=True, capture_output=True, timeout=timeout, check=True).stdout


def main():
    if len(sys.argv) != 5:
        raise SystemExit("usage: run-191-theme-device.py <consumer|developer> <29|36> <port> <output-dir>")
    variant, api, port_s, out_s = sys.argv[1:5]
    port = int(port_s)
    if port % 2 or port < 5554:
        raise SystemExit("use an even emulator console port >= 5554")
    suffix = "" if variant == "consumer" else ".developer"
    avd = f"HelixApkUpgrade_API{api}_20260918"
    serial = f"emulator-{port}"
    app_apk = f"app/build/outputs/apk/{variant}/debug/app-{variant}-debug.apk"
    test_apk = f"app/build/outputs/apk/androidTest/{variant}/debug/app-{variant}-debug-androidTest.apk"
    runner = f"com.helix.agent{suffix}.test/com.helix.app.HelixAndroidJUnitRunner"
    theme_class = "com.helix.app.ui.HelixThemeDeviceTest"
    verify_classes = theme_class + ",com.helix.app.ui.ThemeDialogDeviceTest"
    if variant == "developer":
        verify_classes += ",com.helix.app.proot.SubscriptionThemeDeviceTest"
    app_pkg = f"com.helix.agent{suffix}"
    out = Path(out_s)

    # Refuse a borrowed serial and confirm both console ports are free before we own them.
    devices = adb_run([ADB, "devices"]).splitlines()
    if any(l.split()[0] == serial for l in devices[1:] if l.split()):
        raise RuntimeError(f"refusing existing device {serial}")
    for p in (port, port + 1):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", p))

    # Fresh output dir every run (re-runnable; the prior matrix run's evidence is superseded).
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=False)
    artifacts = {}
    for label, src in (("app", app_apk), ("test", test_apk)):
        target = out / f"{label}.apk"
        shutil.copyfile(src, target)
        artifacts[label] = hashlib.sha256(target.read_bytes()).hexdigest()
    (out / "artifacts.json").write_text(json.dumps(artifacts, indent=2))

    with (out / "emulator.log").open("w") as log:
        proc = subprocess.Popen(
            [EMULATOR, "-avd", avd, "-port", str(port), "-read-only", "-no-window", "-no-audio",
             "-no-snapshot", "-no-boot-anim", "-memory", "4096", "-cores", "4",
             "-gpu", "swiftshader_indirect"],
            stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (out / "owner.json").write_text(json.dumps({"pid": proc.pid, "serial": serial, "avd": avd}))

        def device(*argv, timeout=120, check=True):
            if proc.poll() is not None:
                raise RuntimeError("owned emulator exited; refusing a replacement device")
            r = subprocess.run([ADB, "-s", serial, *argv], text=True, capture_output=True, timeout=timeout)
            if check and r.returncode != 0:
                raise subprocess.CalledProcessError(r.returncode, argv, output=r.stdout, stderr=r.stderr)
            return r.stdout

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
            if avd not in device("emu", "avd", "name").splitlines():
                raise RuntimeError("AVD identity mismatch")
            device("shell", "wm", "size", "1080x2400")
            device("shell", "wm", "density", "420")
            device("install", "-r", str(out / "app.apk"), timeout=180)
            device("install", "-r", str(out / "test.apk"), timeout=180)

            # API29's image locks night mode: ordinary shell lacks MODIFY_DAY_NIGHT_MODE.
            # Use the owned test emulator's root shell only; no app permission/config override.
            ui_state = device("shell", "dumpsys", "uimode")
            (out / "initial-uimode.txt").write_text(ui_state)
            night_command = ("su", "0", "cmd") if "mNightModeLocked=true" in ui_state else ("cmd",)

            for mode, night, font in (("light", "no", "1.0"), ("dark", "yes", "1.3")):
                device("shell", *night_command, "uimode", "night", night)
                (out / f"{mode}-uimode.txt").write_text(device("shell", "cmd", "uimode", "night"))
                device("shell", "settings", "put", "system", "font_scale", font)
                (out / f"{mode}-font_scale.txt").write_text(
                    device("shell", "settings", "get", "system", "font_scale"))

                # Two-phase restart protocol in this mode (setup kills; verify is a fresh process).
                setup = device("shell", "am", "instrument", "-w", "-e", "class", theme_class,
                               "-e", "recoveryPhase", "setup", runner, timeout=600, check=False)
                (out / f"{mode}-setup.txt").write_text(setup)
                if "process crashed" not in setup.lower():
                    raise RuntimeError(f"theme setup ({mode}) did not reach the expected process death")
                marker = device("shell", "run-as", app_pkg, "cat", "no_backup/theme-recovery-pid").strip()
                (out / f"{mode}-setup-pid.txt").write_text(marker)
                if not marker.isdigit():
                    raise RuntimeError(f"theme setup ({mode}) did not write a durable pid marker")

                verify = device("shell", "am", "instrument", "-w", "-e", "class", verify_classes,
                                "-e", "recoveryPhase", "verify", "-e", "expectedNight", night,
                                runner, timeout=900, check=False)
                (out / f"{mode}-instrument.txt").write_text(verify)
                print(f"[{mode}] " + (verify.splitlines()[-1] if verify.splitlines() else ""), flush=True)
                # Preserve synthetic screenshots and remote window facts before owned teardown.
                evidence_dirs = ["hxa191-theme"]
                if variant == "developer":
                    evidence_dirs.append("subscription-theme")
                with (out / f"{mode}-rendered-evidence.tar").open("wb") as archive:
                    subprocess.run(
                        [ADB, "-s", serial, "exec-out", "run-as", app_pkg,
                         "tar", "-cf", "-", "-C", "cache", *evidence_dirs],
                        stdout=archive, stderr=subprocess.PIPE, timeout=60, check=True)

                # On-device appearance proof WITH THE APP IN THE FOREGROUND. Post-instrument the
                # test's activity is destroyed, so a bare dumpsys would show the test splash
                # window, not the app. Bring MainActivity forward (in this mode), read its window's
                # status/nav bar appearance, then stop it.
                device("shell", "am", "start", "-W", "-n",
                       f"{app_pkg}/com.helix.app.MainActivity", check=False)
                time.sleep(4)
                win = device("shell", "dumpsys", "window", "windows", timeout=60)
                (out / f"{mode}-dumpsys-window.txt").write_text(win)
                app_block: list = []
                capturing = False
                for l in win.splitlines():
                    if not capturing and "com.helix.app.MainActivity" in l:
                        capturing = True
                    elif capturing and l.strip().startswith("Window #") \
                            and "com.helix.app.MainActivity" not in l:
                        break
                    if capturing:
                        app_block.append(l)
                appearance = [l for l in app_block
                              if any(k in l for k in
                                     ("mLastAppearance", "apr=", "vsysui=", "LIGHT_STATUS", "LIGHT_NAVIGATION"))]
                (out / f"{mode}-appearance.txt").write_text(
                    ("app window:\n" + "\n".join(app_block) + "\n-- appearance flags --\n"
                     + "\n".join(appearance) + "\n") if app_block
                    else "app MainActivity window not found in dumpsys\n")
                device("shell", "am", "force-stop", app_pkg)

                ok = (bool(re.search(r"^OK \([1-9][0-9]* tests?\)", verify, re.M))
                      and "FAILURES!!!" not in verify
                      and "INSTRUMENTATION_FAILED" not in verify
                      and "process crashed" not in verify.lower())
                if not ok:
                    raise RuntimeError(f"theme verify ({mode}) did not report a nonempty passing suite")
        finally:
            if proc.poll() is None:
                os.killpg(proc.pid, signal.SIGTERM)
                try:
                    proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(proc.pid, signal.SIGKILL)
                    proc.wait(timeout=10)
            (out / "closed.json").write_text(json.dumps({"pid": proc.pid, "exit": proc.returncode}))


if __name__ == "__main__":
    sys.exit(main())

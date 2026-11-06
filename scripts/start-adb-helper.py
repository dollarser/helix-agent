#!/usr/bin/env python3
"""Start/stop the installed Helix Advanced helper (Android 12+) over computer ADB. No wireless pairing."""
import argparse
import json
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import sys
import time

PACKAGE = "com.helix.agent.developer"
MAIN = "com.helix.app.automation.adb.AdbHelperMain"


def shell(adb, *args, **kwargs):
    return subprocess.check_output(adb + ["shell", shlex.join(args)], **kwargs)


def process_start(adb, pid):
    if not isinstance(pid, int) or pid <= 0:
        raise ValueError("Invalid helper PID")
    result = subprocess.run(adb + ["shell", "cat", "/proc/" + str(pid) + "/stat"],
                            text=True, capture_output=True)
    if result.returncode:
        return None
    fields = result.stdout.rsplit(")", 1)[1].split()
    return None if fields[0] == "Z" else fields[19]


def boot_identity(adb):
    value = shell(adb, "cat", "/proc/sys/kernel/random/boot_id", text=True).strip()
    if not re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value):
        raise RuntimeError("Cannot establish the device boot identity")
    return value


def stop_helper(adb, receipt):
    if not receipt.get("bootId"):
        raise RuntimeError("Receipt has no device boot identity; no process was stopped")
    if boot_identity(adb) != receipt["bootId"]:
        raise RuntimeError("Device rebooted since this receipt; refusing to stop a process from another boot")
    pid = receipt["pid"]
    current = process_start(adb, pid)
    if current is None:
        return
    if current != receipt["startTicks"]:
        raise RuntimeError("Original helper exited and PID was reused; refusing to stop another process")
    shell(adb, "kill", "-TERM", str(pid))
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        if process_start(adb, pid) != current:
            return
        time.sleep(0.1)
    raise TimeoutError("Helper has not exited; connection outcome is unconfirmed")


def wait_log(adb, remote, prefix, timeout):
    deadline = time.monotonic() + timeout
    text = ""
    while time.monotonic() < deadline:
        result = subprocess.run(adb + ["exec-out", "cat", remote], text=True, capture_output=True)
        text = result.stdout
        for line in text.splitlines():
            if line.startswith(prefix):
                return line.removeprefix(prefix)
        if "HELIX_ADB_FAILED" in text or "Exception" in text or "Error:" in text:
            raise RuntimeError("Helper startup failed:\n" + text[-8000:])
        time.sleep(0.1)
    raise TimeoutError("Helper connection timed out:\n" + text[-8000:])


def cleanup_startup(actions, primary):
    failures = []
    for action in actions:
        try:
            action()
        except Exception as error:
            failures.append(error)
    if failures:
        if primary is None:
            raise RuntimeError("Helper connected, but startup-file cleanup is unconfirmed; keep its receipt") from failures[0]
        for error in failures:
            print("Helper cleanup unconfirmed: " + str(error), file=sys.stderr)


def start_helper(adb, serial, receipt_file):
    boot = boot_identity(adb)
    if int(shell(adb, "getprop", "ro.build.version.sdk", text=True).strip()) < 31:
        raise RuntimeError("Computer helper requires Android 12 / API 31 or newer")
    paths = shell(adb, "pm", "path", PACKAGE, text=True).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:"):
        raise RuntimeError("Prototype requires the installed monolithic Advanced APK")
    apk = paths[0].removeprefix("package:")
    launch = secrets.token_hex(16)
    directory = "/data/local/tmp/helix-adb-" + launch
    startup = directory + "/input"
    log = directory + "/startup.log"
    ready = None
    connected = False
    directory_created = False
    try:
        shell(adb, "mkdir", "-m", "700", directory)
        directory_created = True
        # Detached with no computer input FD. The app's Binder lifetime, not USB/terminal lifetime, owns it.
        command = "(CLASSPATH=" + shlex.quote(apk) + \
            " /system/bin/nohup app_process /system/bin --nice-name=helix_adb_helper " + MAIN + " " + PACKAGE + \
            " " + shlex.quote(startup) + " </dev/null >" + shlex.quote(log) + " 2>&1 &)"
        subprocess.run(adb + ["shell", command], check=True)
        ready = json.loads(wait_log(adb, log, "HELIX_ADB_READY ", 30))
        ready["bootId"] = boot
        if ready.get("uid") != 2000 or ready.get("apk") != apk:
            raise RuntimeError("Helper does not match the installed APK/shell identity")
        ready["startTicks"] = process_start(adb, ready["pid"])
        if ready["startTicks"] is None:
            raise RuntimeError("Helper exited before handoff")
        payload = json.dumps({"launch": launch}) + "\n"
        write = "umask 077; cat > " + shlex.quote(startup + ".tmp") + "; mv " + shlex.quote(startup + ".tmp") + " " + shlex.quote(startup)
        subprocess.run(adb + ["shell", write], input=payload, text=True, check=True, timeout=10)
        wait_log(adb, log, "HELIX_ADB_CONNECTED ", 30)
        if boot_identity(adb) != boot:
            raise RuntimeError("Device rebooted during helper startup; connection was not retained")
        ready.update(serial=serial, state="connected")
        receipt_file.parent.mkdir(parents=True, exist_ok=True)
        receipt_file.write_text(json.dumps(ready, indent=2))
        connected = True
        print("HELIX_ADB_CONNECTED pid=" + str(ready["pid"]) + "; receipt=" + str(receipt_file), flush=True)
    finally:
        primary = sys.exc_info()[1]
        actions = []
        if not connected and ready is not None and ready.get("startTicks") is not None:
            actions.append(lambda: stop_helper(adb, ready))
        # Exact owned temporary paths. No credentials or startup files are retained after handoff.
        if directory_created:
            actions.extend([
                lambda: shell(adb, "rm", "-f", startup, startup + ".tmp", log),
                lambda: shell(adb, "rmdir", directory),
            ])
        cleanup_startup(actions, primary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--ready-file", type=Path, help="Local process receipt, needed for --stop; contains no credential")
    parser.add_argument("--stop", action="store_true", help="Stop only the exact process recorded by this launcher")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9._:-]+", args.serial):
        parser.error("Invalid serial")
    adb = ["adb", "-s", args.serial]
    receipt_file = args.ready_file or Path(__file__).resolve().parents[1] / "build" / "adb-helper" / (args.serial.replace(":", "_") + ".json")
    if args.stop:
        receipt = json.loads(receipt_file.read_text())
        if receipt["serial"] != args.serial:
            raise RuntimeError("Receipt belongs to another device")
        stop_helper(adb, receipt)
        receipt["state"] = "stopped"
        receipt_file.write_text(json.dumps(receipt, indent=2))
        print("HELIX_ADB_STOPPED pid=" + str(receipt["pid"]), flush=True)
    else:
        start_helper(adb, args.serial, receipt_file)
    return 0


if __name__ == "__main__":
    sys.exit(main())

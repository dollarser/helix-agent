#!/usr/bin/env python3
"""Launch an exclusive emulator, run a bounded instrumentation suite, always close it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import socket
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent))
from owned_acceptance import split_recovery_log


def passed(output):
    return bool(re.search(r"^OK \([1-9][0-9]* tests?\)", output, re.M)) and not any(
        marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")
    )


def run(args):
    sdk = Path(os.environ["ANDROID_HOME"])
    adb = str(sdk / "platform-tools/adb")
    serial = f"emulator-{args.port}"
    devices = subprocess.check_output([adb, "devices"], text=True)
    if any(line.split()[0] == serial for line in devices.splitlines()[1:] if line.split()):
        raise RuntimeError(f"Refusing existing device {serial}")
    if args.port % 2 or not 5554 <= args.port <= 5750:
        raise ValueError("Use an even emulator console port between 5554 and 5750")
    for port in (args.port, args.port + 1):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", port))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    artifacts = {}
    for label, source in (("app", args.apk), ("test", args.test_apk)):
        target = output / f"{label}.apk"
        shutil.copyfile(source, target)
        artifacts[label] = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / "artifacts.json").write_text(json.dumps(artifacts, indent=2))
    command = [str(sdk / "emulator/emulator"), "-avd", args.avd, "-port", str(args.port),
               "-read-only", "-no-window", "-no-audio", "-no-snapshot", "-no-boot-anim",
               "-memory", str(args.memory_mb), "-cores", str(args.cores), "-gpu", "swiftshader_indirect"]
    (output / "emulator-config.json").write_text(json.dumps({"memoryMb": args.memory_mb, "cores": args.cores}))
    with (output / "emulator.log").open("w") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        (output / "owner.json").write_text(json.dumps({"pid": process.pid, "serial": serial,
                                                     "avd": args.avd, "started": time.time()}, indent=2))

        def device(*argv, timeout=60):
            if process.poll() is not None:
                raise RuntimeError("Owned emulator exited; refusing to access a replacement device")
            return subprocess.run([adb, "-s", serial, *argv], text=True, capture_output=True,
                                  timeout=timeout, check=True).stdout

        def interrupted(signum, frame):
            raise KeyboardInterrupt(f"Signal {signum}")

        old_handlers = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
        try:
            deadline = time.monotonic() + 240
            while time.monotonic() < deadline:
                try:
                    if device("shell", "getprop", "sys.boot_completed", timeout=10).strip() == "1":
                        break
                except subprocess.CalledProcessError:
                    pass
                time.sleep(2)
            else:
                raise TimeoutError("Emulator boot deadline exceeded")
            # Grow the main ring buffer first: a full matrix class emits enough TestRunner lines
            # (plus app chatter) that the default 256KB buffer can evict early "assumption
            # failed" lines before the post-run capture, losing skip evidence.
            device("shell", "logcat", "-G", "16M")
            # Confirm the newly launched instance, never attach to a borrowed device.
            if args.avd not in device("emu", "avd", "name").splitlines():
                raise RuntimeError("AVD identity mismatch")
            (output / "device.json").write_text(json.dumps({
                "api": int(device("shell", "getprop", "ro.build.version.sdk").strip()),
                "model": device("shell", "getprop", "ro.product.model").strip(),
                "pageSize": int(device("shell", "getconf", "PAGESIZE").strip()),
                "kind": "owned-emulator",
            }, indent=2))
            if args.airplane_mode:
                device("shell", "svc", "wifi", "disable")
                device("shell", "svc", "data", "disable")
            device("shell", "wm", "size", "1080x2400")
            device("shell", "wm", "density", "420")
            if args.night_mode:
                api = int(device("shell", "getprop", "ro.build.version.sdk").strip())
                prefix = ("shell", "su", "0") if api == 29 else ("shell",)
                device(*prefix, "cmd", "uimode", "night", args.night_mode)
                device("shell", "settings", "put", "system", "font_scale", "1.3" if args.night_mode == "yes" else "1.0")
                (output / "system-theme.txt").write_text(device("shell", "dumpsys", "uimode"))
            if args.reverse_port:
                device("reverse", f"tcp:{args.reverse_port}", f"tcp:{args.reverse_port}")
            device("install", "-r", str(output / "app.apk"), timeout=120)
            device("install", "-r", str(output / "test.apk"), timeout=120)
            if args.grant_shared_storage:
                app_package = args.runner.split("/", 1)[0].removesuffix(".test")
                api = int(device("shell", "getprop", "ro.build.version.sdk").strip())
                if api >= 30:
                    device("shell", "appops", "set", app_package, "MANAGE_EXTERNAL_STORAGE", "allow")
                else:
                    for permission in ("READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE"):
                        device("shell", "pm", "grant", app_package, "android.permission." + permission)
            if args.recovery_setup_class:
                setup = device("shell", "am", "instrument", "-w", "-e", "class", args.recovery_setup_class,
                               "-e", "recoveryPhase", "setup", args.runner, timeout=args.timeout)
                (output / "recovery-setup.txt").write_text(setup)
                if "process crashed" not in setup.lower():
                    raise RuntimeError("Recovery setup did not reach the expected process death")
                app_package = args.runner.split("/", 1)[0].removesuffix(".test")
                old_pid = device("shell", "run-as", app_package, "cat", args.recovery_pid_file).strip()
                if not old_pid.isdigit():
                    raise RuntimeError("Missing durable setup process identity")
                (output / "process-stop.txt").write_text("Process.killProcess at publication; previous pid=" + old_pid)
            extras = []
            for argument in args.instrument_arg:
                key, value = argument.split("=", 1)
                extras.extend(["-e", key, value])
            (output / "setup-logcat.txt").write_text(device("logcat", "-d", "-t", "20000"))
            # API29 retained setup TestRunner lines in system when only the default
            # buffer was cleared; keep the entire previous phase in setup-logcat.txt.
            device("logcat", "-b", "all", "-c")
            # Stream selected tags while the suite runs. A post-run ring-buffer tail can
            # silently lose early method starts in a noisy Runtime suite.
            with (output / "test-logcat.txt").open("w") as test_log:
                capture = subprocess.Popen([adb, "-s", serial, "logcat", "-v", "threadtime", "-s",
                                            "TestRunner", "System.out", "HelixChat", "HelixFilePreview",
                                            "HelixAcceptance"], stdout=test_log, stderr=subprocess.STDOUT)
                try:
                    result = device("shell", "am", "instrument", "-w", "-e", "class", args.classes, *extras,
                                    args.runner, timeout=args.timeout)
                    capture_deadline = time.monotonic() + 5
                    while capture.poll() is None and time.monotonic() < capture_deadline:
                        if "TestRunner: run finished:" in (output / "test-logcat.txt").read_text():
                            break
                        time.sleep(.1)
                finally:
                    capture.terminate()
                    try:
                        capture.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        capture.kill()
                        capture.wait(timeout=10)
            (output / "instrumentation.txt").write_text(result)
            if args.recovery_setup_class:
                raw_path = output / "test-logcat.txt"
                raw = raw_path.read_text()
                current, prior = split_recovery_log(raw, int(old_pid))
                (output / "test-logcat-all-phases.txt").write_text(raw)
                (output / "setup-late-logcat.txt").write_text(prior)
                raw_path.write_text(current)
                (output / "log-phase-boundary.json").write_text(json.dumps({
                    "setupPid": int(old_pid), "identitySource": "process-stop.txt",
                    "allPhasesSha256": hashlib.sha256(raw.encode()).hexdigest(),
                    "verificationSha256": hashlib.sha256(current.encode()).hexdigest(),
                    "setupTailSha256": hashlib.sha256(prior.encode()).hexdigest(),
                }, indent=2))
            print(result, flush=True)
            if not passed(result):
                (output / "failure-logcat.txt").write_text(device("logcat", "-d"))
                device("shell", "uiautomator", "dump", "/sdcard/helix-test-window.xml", timeout=30)
                (output / "failure-window.xml").write_text(device("shell", "cat", "/sdcard/helix-test-window.xml"))
                raise RuntimeError("Instrumentation did not report a nonempty passing JUnit suite")
            if getattr(args, "after_script", None):
                if process.poll() is not None:
                    raise RuntimeError("Owned emulator exited before follow-up")
                with (output / "follow-up.txt").open("w") as followup:
                    subprocess.run(["python3", args.after_script, serial, str(output)],
                                   stdout=followup, stderr=subprocess.STDOUT, check=True, timeout=args.timeout)
        except subprocess.CalledProcessError as error:
            (output / "command-failure.txt").write_text((error.stdout or "") + (error.stderr or ""))
            raise
        except subprocess.TimeoutExpired as error:
            (output / "timeout.txt").write_text(str(error))
            raise
        finally:
            # Terminate only our process group; no broad adb emu kill or serial-based cleanup.
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=10)
            (output / "closed.json").write_text(json.dumps({"pid": process.pid, "exit": process.returncode}))
            for sig, handler in old_handlers.items():
                signal.signal(sig, handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--port", type=int, default=5598)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--test-apk", required=True)
    parser.add_argument("--classes", required=True)
    parser.add_argument("--recovery-setup-class")
    parser.add_argument("--recovery-pid-file", default="no_backup/recovery-device-pid",
                        help="Durable setup-process identity written by the selected recovery test")
    parser.add_argument("--runner", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--memory-mb", type=int, choices=(2048, 4096), default=2048)
    parser.add_argument("--cores", type=int, choices=(2, 4), default=2)
    parser.add_argument("--reverse-port", type=int)
    parser.add_argument("--grant-shared-storage", action="store_true")
    parser.add_argument("--airplane-mode", action="store_true",
                        help="Cut the network (disable wifi + data) for the offline scenario")
    parser.add_argument("--night-mode", choices=("yes", "no"), help="Owned emulator system mode; dark also uses font scale 1.3")
    parser.add_argument("--instrument-arg", action="append", default=[])
    parser.add_argument("--after-script", help="Run a checked Python follow-up on this owned serial before teardown")
    run(parser.parse_args())

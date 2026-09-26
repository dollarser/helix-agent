#!/usr/bin/env python3
"""Run androidTest classes one at a time on a connected device/emulator with strict single-writer lock, atomic results, and crash recovery.

Key invariants:
- Single runner lock (<out-dir>/.runner.lock): fails closed if an active runner owns out-dir.
- Atomic per-class result (<out-dir>/results/<cls>.json and logs/<cls>.log).
- Single-threaded aggregator generates summary.tsv and summary.json at finish.
- Device state verification and crash recovery probe after any process crash or timeout.
"""

import argparse
import atexit
import datetime
import fcntl
import hashlib
import json
import os
import re
import subprocess
import sys
import time
from typing import Dict, List, Optional, Tuple

import importlib.util

def _load_summarizer():
    curr_dir = os.path.dirname(os.path.abspath(__file__))
    sum_script = os.path.join(curr_dir, "summarize-current-device-baseline.py")
    spec = importlib.util.spec_from_file_location("summarize_baseline", sum_script)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

_summarizer = _load_summarizer()
KNOWN_PHASE_RUNNER_CLASSES = _summarizer.KNOWN_PHASE_RUNNER_CLASSES
KNOWN_EXISTING_FAILURES = _summarizer.KNOWN_EXISTING_FAILURES
KNOWN_ENVIRONMENT_LIMITATIONS = _summarizer.KNOWN_ENVIRONMENT_LIMITATIONS
summarize = _summarizer.summarize

HEALTH_PROBE_CLASS = "com.helix.app.engine.TurnReviewResolutionDeviceTest#deterministicReviewClosesOldTurnAndGoalRunWithoutOpeningAnotherModelCall"


def get_git_commit() -> str:
    try:
        out = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        return out
    except Exception:
        return "UNKNOWN"


def atomic_write_text(path: str, content: str) -> None:
    """Durably replace one small evidence file without exposing a partial write."""
    tmp = f"{path}.tmp.{os.getpid()}"
    with open(tmp, "w", encoding="utf-8") as fp:
        fp.write(content)
        fp.flush()
        os.fsync(fp.fileno())
    os.replace(tmp, path)


def atomic_write_json(path: str, payload: Dict) -> None:
    atomic_write_text(path, json.dumps(payload, indent=2) + "\n")


def acquire_single_writer_lock(lock_file: str, payload: Dict) -> int:
    """Acquire an OS-backed non-blocking exclusive lock and retain its fd for this process."""
    fd = os.open(lock_file, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        try:
            os.lseek(fd, 0, os.SEEK_SET)
            raw = os.read(fd, 8192).decode("utf-8", errors="replace").strip()
            owner = json.loads(raw) if raw else {}
        except Exception:
            owner = {}
        os.close(fd)
        raise RuntimeError(
            f"Another runner owns {lock_file}: pid={owner.get('pid')} run_id={owner.get('run_id')}",
        )

    encoded = (json.dumps(payload, indent=2) + "\n").encode("utf-8")
    os.ftruncate(fd, 0)
    os.lseek(fd, 0, os.SEEK_SET)
    os.write(fd, encoded)
    os.fsync(fd)

    def release() -> None:
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        except OSError:
            pass
        try:
            os.close(fd)
        except OSError:
            pass

    atexit.register(release)
    return fd


def run_cmd(cmd: List[str], timeout: Optional[float] = None) -> Tuple[int, str, str]:
    """Runs a command and returns (returncode, stdout, stderr)."""
    try:
        p = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=timeout,
        )
        return p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as e:
        return -999, e.stdout or "", (e.stderr or "") + "\nCommand timed out."


def check_device_online(serial: str, adb_path: str = "adb") -> bool:
    rc, out, _ = run_cmd([adb_path, "-s", serial, "get-state"], timeout=10)
    return rc == 0 and out.strip() == "device"


def run_health_probe(serial: str, runner: str, adb_path: str = "adb") -> bool:
    """Runs a fast (150ms) health probe test to ensure the runner and app process can execute."""
    cmd = [
        adb_path,
        "-s",
        serial,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        HEALTH_PROBE_CLASS,
        runner,
    ]
    rc, out, _ = run_cmd(cmd, timeout=30)
    return "OK (1 test)" in out


def perform_recovery_probe(serial: str, target_pkg: str, test_pkg: str, runner: str, adb_path: str = "adb") -> bool:
    """Checks device state and test runner responsiveness. Attempts minimal recovery if needed."""
    print("  [Recovery] Checking device state...")
    if not check_device_online(serial, adb_path):
        print("  [Recovery] Device is not online! Waiting up to 10s...")
        time.sleep(5)
        if not check_device_online(serial, adb_path):
            print("  [Recovery] Device failed to respond to get-state.")
            return False

    rc, boot, _ = run_cmd([adb_path, "-s", serial, "shell", "getprop", "sys.boot_completed"], timeout=10)
    if boot.strip() != "1":
        print(f"  [Recovery] Warning: sys.boot_completed = {boot.strip()}")

    print("  [Recovery] Running health probe...")
    if run_health_probe(serial, runner, adb_path):
        print("  [Recovery] Health probe PASSED. Runner is intact.")
        return True

    print("  [Recovery] Health probe failed. Performing minimal package reset...")
    run_cmd([adb_path, "-s", serial, "shell", "pm", "clear", target_pkg], timeout=15)
    run_cmd([adb_path, "-s", serial, "shell", "am", "force-stop", target_pkg], timeout=10)
    run_cmd([adb_path, "-s", serial, "shell", "am", "force-stop", test_pkg], timeout=10)
    time.sleep(2)

    if run_health_probe(serial, runner, adb_path):
        print("  [Recovery] Health probe PASSED after package reset.")
        return True

    print("  [Recovery] Health probe FAILED after package reset. Unrecoverable runner error.")
    return False


def parse_instrumentation_log(cls_name: str, log_content: str, return_code: int) -> Tuple[str, str]:
    """Analyzes the raw instrumentation log to produce a verdict and details."""
    has_ok = bool(re.search(r"^OK \(\d+ tests?\)", log_content, re.M))
    has_failure = bool(re.search(r"^FAILURES!!!", log_content, re.M))
    has_crash = bool(re.search(r"^INSTRUMENTATION_RESULT:\s*shortMsg=Process crashed", log_content, re.M))
    has_aborted = bool(re.search(r"^(INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED)", log_content, re.M))

    # A process crash/timeout is infrastructure evidence, never a phase-runner shortcut.
    if has_crash or has_aborted or return_code == -999:
        if return_code == -999:
            return "NO_VERDICT / PROCESS_CRASH", "Execution timed out"
        crash_m = re.search(r"^INSTRUMENTATION_RESULT:\s*shortMsg=(.*)$", log_content, re.M)
        msg = crash_m.group(1).strip() if crash_m else "Process crashed or aborted"
        return "NO_VERDICT / PROCESS_CRASH", msg

    # Check for explicit phase-runner requirement patterns in output.
    phase_runner_patterns = [
        "Use the two-phase owned runner",
        "Use the mandatory host storage phases",
        "Run the host-controlled granted and revoked phases",
        "Host must grant storage before instrumentation",
        "recoveryPhase",
    ]
    for pattern in phase_runner_patterns:
        if pattern in log_content:
            return "PHASE_RUNNER_REQUIRED", f"Detected phase runner contract: {pattern}"

    if has_ok:
        # Check if all tests were skipped
        status_codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)", log_content, re.M)
        # Codes != 1 (1 is test start)
        test_codes = [c for c in status_codes if c != "1"]
        if test_codes and all(c in ("-4", "-3") for c in test_codes):
            return "SKIP / ASSUMPTION", "All tests skipped via assumption or ignore"
        ok_m = re.search(r"^OK \((\d+ tests?)\)", log_content, re.M)
        details = ok_m.group(0) if ok_m else "OK"
        return "PASS", details

    if has_failure:
        fail_m = re.search(r"^Tests run:\s*(\d+),\s*Failures:\s*(\d+)", log_content, re.M)
        details = fail_m.group(0) if fail_m else "FAILURES"
        # Check for touch injection limitation
        if "Failed to inject touch input" in log_content:
            return "ENVIRONMENT_LIMITATION", "Failed to inject touch input in windowless emulator"
        return "FAIL", details

    return "UNRESOLVED", "No recognizable verdict line in log"


def main():
    parser = argparse.ArgumentParser(description="Isolated AndroidTest Runner")
    parser.add_argument("classes_file", help="Path to classes file (.txt) or manifest (.json)")
    parser.add_argument("--out-dir", default=None, help="Output directory for logs and results")
    parser.add_argument("--flavor", choices=["consumer", "developer"], default="consumer", help="App flavor")
    parser.add_argument("--serial", default="emulator-5554", help="ADB device serial")
    parser.add_argument("--timeout", type=int, default=180, help="Per-class timeout in seconds")
    parser.add_argument("--manifest", default=None, help="Manifest path for aggregation validation")
    args = parser.parse_args()

    # Find adb
    adb_path = "adb"
    sdk_platform_tools = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
    if os.path.isfile(sdk_platform_tools) and os.access(sdk_platform_tools, os.X_OK):
        adb_path = sdk_platform_tools

    git_commit = get_git_commit()
    started_at = datetime.datetime.now(datetime.timezone.utc).isoformat()
    run_id = f"run-{int(time.time())}"

    # Target packages
    if args.flavor == "consumer":
        target_pkg = "com.helix.agent"
    else:
        target_pkg = "com.helix.agent.developer"
    test_pkg = f"{target_pkg}.test"
    runner = f"{test_pkg}/com.helix.app.HelixAndroidJUnitRunner"

    # Setup output directory
    if args.out_dir:
        out_dir = os.path.abspath(args.out_dir)
    else:
        out_dir = f"/tmp/helix-device-baseline/{git_commit[:8]}/{run_id}"

    results_dir = os.path.join(out_dir, "results")
    logs_dir = os.path.join(out_dir, "logs")
    os.makedirs(results_dir, exist_ok=True)
    os.makedirs(logs_dir, exist_ok=True)

    # 1. Single-writer lock enforcement. flock() closes the TOCTOU window that exists with
    # exists()+open(); the lock remains held by this process until exit, even after exceptions.
    lock_file = os.path.join(out_dir, ".runner.lock")
    current_pid = os.getpid()
    try:
        acquire_single_writer_lock(
            lock_file,
            {"pid": current_pid, "run_id": run_id, "started_at": started_at},
        )
    except RuntimeError as exc:
        print(f"FATAL: {exc}", file=sys.stderr)
        sys.exit(1)

    # Load classes
    classes = []
    classes_file = os.path.abspath(args.classes_file)
    if classes_file.endswith(".json"):
        with open(classes_file, "r", encoding="utf-8") as fp:
            data = json.load(fp)
            classes = data.get("classes", [])
        manifest_path = classes_file
    else:
        with open(classes_file, "r", encoding="utf-8") as fp:
            classes = [line.strip() for line in fp if line.strip() and not line.startswith("#")]
        manifest_path = args.manifest

    # Deduplicate classes preserving order
    seen = set()
    unique_classes = []
    for c in classes:
        if c not in seen:
            seen.add(c)
            unique_classes.append(c)
        else:
            print(f"Warning: duplicate class in input list: {c}")

    class_list_text = "\n".join(unique_classes) + "\n"
    class_list_sha256 = hashlib.sha256(class_list_text.encode("utf-8")).hexdigest()

    run_meta = {
        "run_id": run_id,
        "pid": current_pid,
        "git_commit": git_commit,
        "class_list_sha256": class_list_sha256,
        "class_count": len(unique_classes),
        "started_at": started_at,
        "device_serial": args.serial,
        "flavor": args.flavor,
        "target_package": target_pkg,
        "test_package": test_pkg,
        "runner": runner,
        "out_dir": out_dir,
    }

    run_json_path = os.path.join(out_dir, "run.json")
    atomic_write_json(run_json_path, run_meta)

    print("=" * 60)
    print("STARTING ISOLATED DEVICE BASELINE RUN")
    print(f"Run ID:        {run_id}")
    print(f"Commit:        {git_commit}")
    print(f"Device:        {args.serial}")
    print(f"Flavor:        {args.flavor}")
    print(f"Output:        {out_dir}")
    print(f"Classes:       {len(unique_classes)} unique")
    print("=" * 60)

    # Initial device check
    if not check_device_online(args.serial, adb_path):
        print(f"FATAL: Device {args.serial} is not online or ready.", file=sys.stderr)
        sys.exit(1)

    total_classes = len(unique_classes)
    for idx, cls in enumerate(unique_classes, start=1):
        log_path = os.path.join(logs_dir, f"{cls}.log")
        result_path = os.path.join(results_dir, f"{cls}.json")

        t0 = time.time()

        # A normal single-stage run is invalid for these classes by contract. Account for the
        # class explicitly instead of executing a known-invalid phase and then interpreting its
        # arbitrary assertion failure as product evidence.
        if cls in KNOWN_PHASE_RUNNER_CLASSES:
            class_result = {
                "class": cls,
                "verdict": "PHASE_RUNNER_REQUIRED",
                "details": KNOWN_PHASE_RUNNER_CLASSES[cls],
                "duration_sec": 0.0,
                "log_path": log_path,
            }
            atomic_write_text(log_path, f"PHASE_RUNNER_REQUIRED: {KNOWN_PHASE_RUNNER_CLASSES[cls]}\n")
            atomic_write_json(result_path, class_result)
            print(f"[{idx:3d}/{total_classes:3d}] {cls:<58s} -> {'PHASE_RUNNER_REQUIRED':<22s} (0.00s)")
            continue

        # Step 1: Clean state before running class
        run_cmd([adb_path, "-s", args.serial, "shell", "pm", "clear", target_pkg], timeout=15)
        run_cmd([adb_path, "-s", args.serial, "shell", "am", "force-stop", target_pkg], timeout=10)
        run_cmd([adb_path, "-s", args.serial, "shell", "am", "force-stop", test_pkg], timeout=10)

        # Step 2: Run instrumentation
        cmd = [
            adb_path,
            "-s",
            args.serial,
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            cls,
            runner,
        ]

        rc, out, err = run_cmd(cmd, timeout=args.timeout)
        duration = time.time() - t0

        raw_log = out + ("\n" + err if err else "")
        atomic_write_text(log_path, raw_log)

        # Step 3: Parse verdict
        verdict, details = parse_instrumentation_log(cls, raw_log, rc)

        # Step 4: Crash recovery if needed
        if verdict == "NO_VERDICT / PROCESS_CRASH":
            print(f"\n[CRASH DETECTED] Class {cls} caused process crash. Triggering recovery probe...")
            recovered = perform_recovery_probe(args.serial, target_pkg, test_pkg, runner, adb_path)
            if not recovered:
                print("FATAL: BASELINE_INFRA_FAILURE: Emulator recovery probe failed! Aborting run.", file=sys.stderr)
                # Record result for current class before aborting
                class_result = {
                    "class": cls,
                    "verdict": verdict,
                    "details": f"{details} (Recovery failed: BASELINE_INFRA_FAILURE)",
                    "duration_sec": duration,
                    "log_path": log_path,
                }
                atomic_write_json(result_path, class_result)
                sys.exit(4)

        # Step 5: Save class result JSON
        class_result = {
            "class": cls,
            "verdict": verdict,
            "details": details,
            "duration_sec": duration,
            "log_path": log_path,
        }
        atomic_write_json(result_path, class_result)

        print(f"[{idx:3d}/{total_classes:3d}] {cls:<58s} -> {verdict:<22s} ({duration:.2f}s)")

    # Run complete: invoke aggregator
    print("\nAll classes executed. Aggregating baseline...")
    summarize(out_dir, manifest_path)

    print(f"\nRun complete. Full summary written to:\n  {out_dir}/summary.tsv\n  {out_dir}/summary.json")


if __name__ == "__main__":
    main()

"""Read immutable owned-runner output; never infer method success from a class name."""
import hashlib
import json
from pathlib import Path
import re

from acceptance_reports import MAX_FILE_BYTES, require, safe_load_json, safe_resolve_path, unique_object_hook

EVENT = re.compile(r"TestRunner:\s*(started|finished|failed|assumption failed|ignored):\s*([^\s(]+)\(([^)]+)\)\s*$")
THREADTIME_PID = re.compile(r"^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+(\d+)\s+\d+\s+")


def split_recovery_log(text, setup_pid):
    """Keep both phases, separating only the known dead setup process identity."""
    require(type(setup_pid) is int and setup_pid > 0, "Invalid setup PID")
    current, setup = [], []
    for line in text.splitlines(keepends=True):
        match = THREADTIME_PID.match(line)
        (setup if match and int(match[1]) == setup_pid else current).append(line)
    return "".join(current), "".join(setup)


def add_owned_arguments(parser):
    parser.add_argument("--owned-run", type=Path, help="Collect an existing owned-runner directory (device batch only)")
    parser.add_argument("--expected-methods", type=Path,
                        help="JSON object with methods array, fixed before execution; required with --owned-run")


def collect_from_arguments(args):
    require(args.manifest is None, "--owned-run and --manifest are mutually exclusive")
    require(args.expected_methods is not None, "--owned-run requires --expected-methods")
    expected = safe_load_json(args.expected_methods).get("methods")
    require(isinstance(expected, list) and all(isinstance(item, str) for item in expected), "Invalid methods array")
    report = collect_owned(args.owned_run, expected)
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    (args.output / "report.md").write_text(
        f"# {report['verdict']}\n\nOwned instrumentation batch only; not whole-HXA acceptance.\n\n"
        + "\n".join(f"- {test['test_class']}#{test['test_method']}: {test['status']}" for test in report["tests"]) + "\n")
    return 0 if report["verdict"] == "DEVICE_BATCH_PASS" else 1


def verify_manifest_batch(data, base_dir):
    if data.get("mode") != "real" or not any(scene.get("status") == "passed" for scene in data["scenes"]):
        return
    require(isinstance(data.get("owned_run"), str), "Real passing manifest requires owned_run evidence")
    directory = safe_resolve_path(data["owned_run"], base_dir)
    expected = data.get("expected_methods")
    require(isinstance(expected, list) and all(isinstance(item, str) for item in expected), "Missing expected_methods")
    batch = collect_owned(directory, expected)
    require(batch["verdict"] == "DEVICE_BATCH_PASS", "Underlying owned batch did not pass all selected methods")
    for label in ("app", "test"):
        require(data.get(f"{label}_apk_sha256") == batch["artifacts"][label], "Manifest APK identity differs from actual artifact")
    require(data["device_lifecycle"]["owner_pid"] == batch["device_lifecycle"]["owner"]["pid"], "Manifest owner PID mismatch")
    for scene in data["scenes"]:
        if scene.get("status") == "passed":
            safe_resolve_path(str(safe_resolve_path(scene["source_ref"], base_dir)), directory)
            require(f"{scene['test_class']}#{scene['test_method']}" in expected,
                    "Scene method was not selected in underlying batch")


def test_records(text):
    records = {}
    pending_assumption = None
    for line in text.splitlines():
        if "HelixAcceptance:" in line:
            data = json.loads(line.split("HelixAcceptance:", 1)[1].strip(), object_pairs_hook=unique_object_hook,
                              parse_constant=lambda value: (_ for _ in ()).throw(ValueError(f"Invalid metric: {value}")))
            key = f"{data.get('test_class')}#{data.get('test_method')}"
            require(key in records and not records[key].get("finished"), "Metric emitted outside its running test")
            require("metrics" not in records[key] and isinstance(data.get("metrics"), dict), "Duplicate or invalid metric record")
            records[key]["metrics"] = data["metrics"]
        if pending_assumption and "AssumptionViolatedException:" in line:
            records[pending_assumption]["skip_reason"] = line.split("AssumptionViolatedException:", 1)[1].strip()[:512]
            pending_assumption = None
        match = EVENT.search(line)
        if not match:
            continue
        event, method, cls = match.groups()
        key = f"{cls}#{method}"
        if event == "started":
            require(key not in records, f"Duplicate test execution: {key}")
            records[key] = {"test_class": cls, "test_method": method, "status": "incomplete"}
        elif event == "ignored":
            require(key not in records, f"Duplicate ignored test: {key}")
            records[key] = {"test_class": cls, "test_method": method, "status": "skipped"}
        else:
            require(key in records, f"Test event without start: {key}")
            record = records[key]
            require(not record.get("finished"), f"Test event after finish: {key}")
            if event == "finished":
                record["finished"] = True
                if record["status"] == "incomplete":
                    record["status"] = "passed"
            else:
                record["status"] = "skipped" if event == "assumption failed" else "failed"
                if event == "assumption failed":
                    pending_assumption = key
    require(bool(records), "No method-level TestRunner events")
    return records


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def collect_owned(directory: Path, expected):
    """Verify owner closure, copied APKs, raw method events, and the selected method set.

    This proves a device batch only. It does not establish physical hardware, product
    journey metrics, source-to-APK provenance, or an entire HXA's completion.
    """
    directory = directory.resolve()
    require(bool(expected) and len(set(expected)) == len(expected), "Expected methods must be nonempty and unique")
    owner = safe_load_json(directory / "owner.json", directory)
    closed = safe_load_json(directory / "closed.json", directory)
    require(type(owner.get("pid")) is int and owner["pid"] > 0, "Invalid owner PID")
    require(closed.get("pid") == owner["pid"] and type(closed.get("exit")) is int,
            "Owned emulator closure is missing or belongs to another process")
    require(re.fullmatch(r"emulator-\d+", owner.get("serial", "")) is not None, "Invalid owned emulator serial")
    artifacts = safe_load_json(directory / "artifacts.json", directory)
    for label in ("app", "test"):
        require(sha256(directory / f"{label}.apk") == artifacts.get(label), f"{label} APK checksum mismatch")
    raw = directory / "test-logcat.txt"
    require(raw.stat().st_size <= MAX_FILE_BYTES, "Test log exceeds evidence bound")
    phase_files = []
    if (directory / "log-phase-boundary.json").exists():
        phase = safe_load_json(directory / "log-phase-boundary.json", directory)
        phase_files = ["log-phase-boundary.json", "test-logcat-all-phases.txt", "setup-late-logcat.txt", "process-stop.txt"]
        for name in phase_files:
            require((directory / name).stat().st_size <= MAX_FILE_BYTES, "Phase evidence exceeds bound")
        original = (directory / "test-logcat-all-phases.txt").read_text()
        selected, prior = split_recovery_log(original, phase.get("setupPid"))
        require((directory / "process-stop.txt").read_text().endswith(f"previous pid={phase['setupPid']}"),
                "Setup PID is not backed by the recorded process death")
        require(selected == raw.read_text() and prior == (directory / "setup-late-logcat.txt").read_text(),
                "Phase projection differs from the preserved full log")
        for name, key in (("test-logcat-all-phases.txt", "allPhasesSha256"),
                          ("test-logcat.txt", "verificationSha256"), ("setup-late-logcat.txt", "setupTailSha256")):
            require(sha256(directory / name) == phase.get(key), "Phase checksum mismatch")
    records = test_records(raw.read_text())
    require(set(expected) == set(records),
            f"Method coverage mismatch: missing={sorted(set(expected) - records.keys())}, "
            f"unexpected={sorted(records.keys() - set(expected))}")
    instrumentation = directory / "instrumentation.txt"
    require(instrumentation.stat().st_size <= MAX_FILE_BYTES, "Instrumentation output exceeds evidence bound")
    summary = instrumentation.read_text()
    success = re.findall(r"^OK \((\d+) tests?\)", summary, re.M)
    has_failure = any(marker in summary for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
    counts = {status: sum(record["status"] == status for record in records.values())
              for status in ("passed", "failed", "skipped", "incomplete")}
    require(not has_failure or counts["failed"] > 0 or counts["incomplete"] > 0,
            "Instrumentation reports failure but method records claim only successful/skipped tests")
    if not has_failure:
        require(len(success) == 1 and int(success[0]) == len(records), "Instrumentation and method counts differ")
    return {
        "schema_version": 1,
        "verdict": "DEVICE_BATCH_PASS" if counts["passed"] == len(records) and not has_failure else "DEVICE_BATCH_INCOMPLETE",
        "scope": "owned instrumentation batch; not whole-HXA acceptance",
        "device_lifecycle": {"owner": owner, "closed": closed},
        "artifacts": artifacts,
        "source_refs": {name: sha256(directory / name)
                        for name in ["owner.json", "closed.json", "artifacts.json", "test-logcat.txt", "instrumentation.txt", *phase_files]},
        "counts": {"expected": len(expected), "observed": len(records), **counts},
        "tests": list(records.values()),
    }

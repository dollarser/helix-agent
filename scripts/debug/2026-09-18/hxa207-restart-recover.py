#!/usr/bin/env python3
"""HXA-207 restart RECOVER phase (runs as run-owned-emulator-207.py --after-script).

The seeded app process is force-stopped (a fresh process starts next launch; filesDir / Room are
preserved), then recoverExtensionJourneyScope is instrumented in that NEW process to prove the
durable scope (skill enablement + session permission mode) persists while the in-memory MCP
connection does NOT auto-re-enable. The recover instrumentation output is printed to stdout so the
runner records it in follow-up.txt for the summarizer.
"""
import os
import json
from pathlib import Path
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from owned_acceptance import sha256, split_recovery_log, test_records

serial, _output = sys.argv[1:]
output = Path(_output)
owner = json.loads((output / "owner.json").read_text())
assert owner["serial"] == serial and not (output / "closed.json").exists()
os.kill(owner["pid"], 0)
runner = os.environ["HXA207_RUNNER"]
app_package = os.environ["HXA207_APP_PACKAGE"]
adb = os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")
seed_identity = subprocess.check_output(
    [adb, "-s", serial, "exec-out", "run-as", app_package, "cat", "files/extension-journey-restart.txt"],
    text=True, timeout=30)
(output / "extension-seed-identity.txt").write_text(seed_identity)
seed_pid = int(seed_identity.splitlines()[1])

print("force-stopping " + app_package + " (new process next launch, filesDir preserved)", flush=True)
subprocess.run([adb, "-s", serial, "shell", "am", "force-stop", app_package],
               text=True, capture_output=True, check=True)
subprocess.run([adb, "-s", serial, "logcat", "-b", "all", "-c"], check=True)

result = subprocess.run([
    adb, "-s", serial, "shell", "am", "instrument", "-w",
    "-e", "class", "com.helix.app.ExtensionJourneyDeviceTest#recoverExtensionJourneyScope",
    "-e", "extensionJourneyPhase", "recover",
    runner,
], text=True, capture_output=True, timeout=600)
(output / "extension-recovery-instrumentation.txt").write_text(result.stdout)
raw = subprocess.check_output(
    [adb, "-s", serial, "logcat", "-d", "-s", "TestRunner"], text=True, timeout=30)
(output / "extension-recovery-all-phases.txt").write_text(raw)
raw, prior = split_recovery_log(raw, seed_pid)
(output / "extension-recovery-logcat.txt").write_text(raw)
(output / "extension-seed-tail.txt").write_text(prior)
(output / "extension-log-phases.json").write_text(json.dumps({
    "seedPid": seed_pid, "identitySource": "extension-seed-identity.txt",
    "allPhasesSha256": sha256(output / "extension-recovery-all-phases.txt"),
    "verificationSha256": sha256(output / "extension-recovery-logcat.txt"),
}, indent=2))
records = test_records(raw)
assert set(records) == {"com.helix.app.ExtensionJourneyDeviceTest#recoverExtensionJourneyScope"}
assert all(row["status"] == "passed" for row in records.values()), "Recovery method did not pass"
sys.stdout.write(result.stdout + ("\n" if result.stdout and not result.stdout.endswith("\n") else ""))
if result.returncode != 0:
    sys.stderr.write("recover instrument exit=" + str(result.returncode) + "\n" + result.stderr + "\n")

ok = bool(re.search(r"^OK \(1 tests?\)", result.stdout, re.M))
clean = not any(marker in result.stdout
                for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
if not (ok and clean):
    sys.stderr.write("recover phase did not report a passing single-test suite\n")
    sys.exit(1)

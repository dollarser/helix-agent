#!/usr/bin/env python3
"""HXA-207 restart RECOVER phase (runs as run-owned-emulator-207.py --after-script).

The seeded app process is force-stopped (a fresh process starts next launch; filesDir / Room are
preserved), then recoverExtensionJourneyScope is instrumented in that NEW process to prove the
durable scope (skill enablement + session permission mode) persists while the in-memory MCP
connection does NOT auto-re-enable. The recover instrumentation output is printed to stdout so the
runner records it in follow-up.txt for the summarizer.
"""
import os
import re
import subprocess
import sys

serial, _output = sys.argv[1:]
runner = os.environ["HXA207_RUNNER"]
app_package = os.environ["HXA207_APP_PACKAGE"]
adb = os.path.join(os.environ["ANDROID_HOME"], "platform-tools", "adb")

print("force-stopping " + app_package + " (new process next launch, filesDir preserved)", flush=True)
subprocess.run([adb, "-s", serial, "shell", "am", "force-stop", app_package],
               text=True, capture_output=True, check=True)

result = subprocess.run([
    adb, "-s", serial, "shell", "am", "instrument", "-w",
    "-e", "class", "com.helix.app.ExtensionJourneyDeviceTest#recoverExtensionJourneyScope",
    "-e", "extensionJourneyPhase", "recover",
    runner,
], text=True, capture_output=True, timeout=600)
sys.stdout.write(result.stdout + ("\n" if result.stdout and not result.stdout.endswith("\n") else ""))
if result.returncode != 0:
    sys.stderr.write("recover instrument exit=" + str(result.returncode) + "\n" + result.stderr + "\n")

ok = bool(re.search(r"^OK \(1 tests?\)", result.stdout, re.M))
clean = not any(marker in result.stdout
                for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"))
if not (ok and clean):
    sys.stderr.write("recover phase did not report a passing single-test suite\n")
    sys.exit(1)

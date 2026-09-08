#!/usr/bin/env python3
"""Run one real PRoot job in verified forced deep idle on a prepared test device.

Run accept-hxa-086-lifecycle.sh first to install the APKs and active RootFS.
This is a bounded forced-idle check, not natural Doze, thermal or soak evidence.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    events = []
    result = {"serial": args.serial, "passed": False, "events": events}

    def adb(*command, timeout=30):
        completed = subprocess.run(
            ["adb", "-s", args.serial, *command],
            capture_output=True, text=True, timeout=timeout,
        )
        events.append({"command": list(command), "exitCode": completed.returncode,
                       "stdout": completed.stdout, "stderr": completed.stderr})
        completed.check_returncode()
        return completed.stdout.strip()

    def idle(*command):
        return adb("shell", "cmd", "deviceidle", *command)

    restore_needed = False
    original_enabled = None
    try:
        original_enabled = idle("enabled", "deep")
        if original_enabled not in ("0", "1"):
            raise RuntimeError("cannot determine whether deep idle is enabled")
        if idle("get", "force") != "false":
            raise RuntimeError("device already has a forced idle state; leave it untouched")
        result["api"] = adb("shell", "getprop", "ro.build.version.sdk")
        result["originalDeepEnabled"] = original_enabled
        restore_needed = True
        idle("enable", "deep")
        idle("force-idle", "deep")
        if idle("get", "deep") != "IDLE" or idle("get", "force") != "true":
            raise RuntimeError("device did not enter forced deep idle")
        output = adb(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "hxa086_host_phase", "1", "-e", "class",
            "com.helix.app.proot.ProotLifecycleE2eDeviceTest#"
            "phaseFirstJobAfterCleanStateRunsByColdBind",
            "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner",
            timeout=240,
        )
        (args.output / "instrumentation.log").write_text(output + "\n")
        if ("OK (1 test)" not in output or "FAILURES!!!" in output
                or re.search(r"INSTRUMENTATION_STATUS_CODE: -[234]|Skipped: [1-9]", output)):
            raise RuntimeError("PRoot job did not pass without failure or assumption skip")
        if idle("get", "deep") != "IDLE" or idle("get", "force") != "true":
            raise RuntimeError("forced deep idle did not persist through the job")
        result["jobPassedInForcedIdle"] = True
    except Exception as error:
        result["error"] = str(error)
    finally:
        if restore_needed:
            try:
                idle("unforce")
                if original_enabled == "0":
                    idle("disable", "deep")
                result["restored"] = (
                    idle("get", "force") == "false"
                    and idle("enabled", "deep") == original_enabled
                )
            except Exception as error:
                result["restoreError"] = str(error)
        result["passed"] = bool(result.get("jobPassedInForcedIdle") and result.get("restored"))
        (args.output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"serial": args.serial, "passed": result["passed"]}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

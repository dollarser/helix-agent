#!/usr/bin/env python3
"""Run a continuous, single-process WebView soak on an explicitly selected emulator.

A short pilot validates the harness; only --seconds 86400 can produce a 24h result.
No app installation, device reset, or configuration changes are performed here.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--workload", choices=["browser", "app"], default="browser")
    parser.add_argument("--seconds", type=int, default=86400)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--memory-sampler", choices=["full", "local"], default="full", help="local avoids calling into the app process during dumpsys")
    parser.add_argument("--observe-breaches", action="store_true", help="App: collect full duration, then fail if any peak exceeded unchanged limits")
    args = parser.parse_args()
    if not args.serial.startswith("emulator-") or not 60 <= args.seconds <= 86400:
        parser.error("requires explicit emulator serial and 60..86400 seconds")
    if args.observe_breaches and args.workload != "app":
        parser.error("--observe-breaches is available only for app workload")
    args.output.mkdir(parents=True, exist_ok=False)
    adb = str(Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))) / "platform-tools/adb")
    prefix = [adb, "-s", args.serial]
    package = "com.helix.feature.browser.test" if args.workload == "browser" else "com.helix.agent"
    component = package + "/androidx.test.runner.AndroidJUnitRunner" if args.workload == "browser" else "com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner"
    test = "com.helix.feature.browser.webview.WebViewResourceLifecycleDeviceTest#continuousResourceSoak" if args.workload == "browser" else "com.helix.app.diagnostics.ContinuousAppResourceDeviceTest#continuousAppResourceSoak"
    def shell(*words):
        return subprocess.check_output(prefix + ["shell", *words], text=True, timeout=30)
    metadata = {"serial": args.serial, "workload": args.workload, "seconds": args.seconds, "memorySampler": args.memory_sampler, "observeBreaches": args.observe_breaches, "startUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
                "workingDiffSha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"])).hexdigest(),
                "api": shell("getprop", "ro.build.version.sdk").strip(), "state": "RUNNING"}
    state_file = args.output / "result.json"
    state_file.write_text(json.dumps(metadata, indent=2))
    started = time.monotonic()
    with (args.output / "instrumentation.log").open("w") as log, (args.output / "logcat.log").open("w") as cat:
        collector = subprocess.Popen(prefix + ["logcat", "-v", "threadtime", "-T", "1"], stdout=cat, stderr=subprocess.STDOUT)
        runner = subprocess.Popen(prefix + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
             test,
             "-e", "helix.soak.seconds", str(args.seconds), "-e", "helix.soak.observe", str(args.observe_breaches).lower(), component], stdout=log, stderr=subprocess.STDOUT)
        try:
            previous = time.monotonic()
            while runner.poll() is None:
                time.sleep(30)
                now = time.monotonic()
                if now - previous > 90:
                    raise RuntimeError("host suspended or sampling gap exceeded 90 seconds")
                previous = now
                if now - started > args.seconds + 180:
                    raise TimeoutError("instrumentation exceeded duration plus 180 seconds")
                with (args.output / "memory.log").open("a") as sample:
                    options = ["--local"] if args.memory_sampler == "local" else []
                    sample.write(f"\nelapsedSeconds={now - started:.1f}\n" + shell("dumpsys", "meminfo", *options, package))
            log.flush()
            output = (args.output / "instrumentation.log").read_text()
            passed = runner.returncode == 0 and "OK (1 test)" in output and "INSTRUMENTATION_STATUS_CODE: 0" in output
            passed = passed and not any(f"INSTRUMENTATION_STATUS_CODE: {code}" in output for code in [-1, -2, -3, -4])
            passed = passed and time.monotonic() - started >= args.seconds
            metadata.update(state="PASS" if passed else "FAIL", elapsedSeconds=time.monotonic() - started)
        except BaseException as error:
            metadata.update(state="FAIL", error=type(error).__name__ + ": " + str(error))
            raise
        finally:
            if runner.poll() is None:
                runner.terminate()
                subprocess.run(prefix + ["shell", "am", "force-stop", package], timeout=30, check=False)
            collector.terminate()
            collector.wait(timeout=15)
            state_file.write_text(json.dumps(metadata, indent=2))
    print(json.dumps(metadata))
    return 0 if metadata["state"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

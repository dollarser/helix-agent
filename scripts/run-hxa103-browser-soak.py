#!/usr/bin/env python3
"""EV-03 / HXA-103 continuous app-resource soak runner -- §5 contract.

Runs the existing `ContinuousAppResourceDeviceTest#continuousAppResourceSoak` (workload "app") or
`WebViewResourceLifecycleDeviceTest#continuousResourceSoak` (workload "browser") as a single-process,
single-instrumentation soak for a preset duration (15m pilot / 2h precheck / same-process 24h).

The fixture is the authoritative resource gate: each cycle it measures its own process (/proc/self)
FD / threads / PSS and ENFORCES the peak gates device-side (master-plan:56, unchanged):
    FD +8, threads +16, PSS +96MiB  (cumulative from baseline to peak).
This runner does NOT re-implement those gates; its job is the §5 host contract:
  1. Fixed artifact identity -- pin git commit + working diff + the INSTALLED app/test APK sha256 so
     the run is attributable to specific artifacts (no app install/reset here; the operator pre-installs).
  2. Single-process tracking -- assert the process running the soak never restarted (same-process 24h).
  3. Sampling-failure attribution -- independent app-process PSS sampling that degrades to a labelled,
     attributed record on failure (never silently dropped, never faked to 0).
  4. Forensics-first judgment -- decide from REAL EVIDENCE: the `am instrument` stdout stream
     (OK (N tests) / FAILURES!!!) + logcat 'TestRunner: run finished' + the fixture's own HelixSoak
     per-cycle log (pid / cycles / fd / threads / pssKb) -- NEVER from the instrumentation exit code
     (teardown SIGKILLs the finished inst process -> -1, benign) or a raw exit 0.

Result states (closed set):
    PASS             the requested-duration soak is fully green (single pid, full duration, no gate drift)
    FAIL_FUNCTIONAL  am-instrument stream not OK / nested fixture failed / process restarted
    FAIL_RESOURCE    a fixture device-side peak gate was breached (FD / threads / PSS drift)
    INFRA_INTERRUPTED host sampling gap, instrumentation timeout, or device loss
    INCONCLUSIVE     green stream but some forensics incomplete (no pid evidence / duration not met)
    CANCELLED        runner stopped cleanly before the requested duration

A pilot/precheck run is a HARNESS VALIDATION, not a 24h result: its state is PASS only if that
short soak was fully green, and its note says so explicitly. Only `--preset p24h` is the 24h EV-03
deliverable. No product feature is added or modified; this is test-harness / evidence only.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

# Per-workload identity: the app process to sample independently, the instrumentation component, and
# the single test method that runs the soak. `test_pkg` is derived from the component's package part.
WORKLOADS = {
    "app": {
        "measure_pkg": "com.helix.agent",
        "component": "com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner",
        "test": "com.helix.app.diagnostics.ContinuousAppResourceDeviceTest#continuousAppResourceSoak",
    },
    "browser": {
        "measure_pkg": "com.helix.feature.browser.test",
        "component": "com.helix.feature.browser.test/androidx.test.runner.AndroidJUnitRunner",
        "test": "com.helix.feature.browser.webview.WebViewResourceLifecycleDeviceTest#continuousResourceSoak",
    },
}

# master-plan:56 -- 15m pilot -> 2h precheck -> same-process 24h.
PRESETS = {"pilot": 900, "precheck": 7200, "p24h": 86400}

# Fixture-enforced peak gates (unchanged). Used only to annotate a FAIL_RESOURCE note, never to
# override the fixture's own device-side assertion.
GATES = {"FD": 8, "thread": 16, "PSS": 96 * 1024}

SAMPLING_INTERVAL = 30      # host app-process sampling tick (seconds)
WATCHDOG_GAP_MAX = 90       # host suspended / sampling gap -> INFRA_INTERRUPTED
TIMEOUT_SLACK = 180         # instrumentation must finish within duration + slack
CASES_PER_CYCLE = 7         # the fixture asserts runCount == 7 per cycle (no skip)
BASELINE_WARMUP_CYCLES = 1  # the fixture runs one runCycle() before the measured loop


def classify(ev):
    """Pure forensics-first judgment. `ev` is a dict of EVIDENCE (see run()); returns
    (state, note, attribution). Device-independent: unit-testable without an emulator.

    Priority: CANCELLED > INFRA > (stream FAIL_RESOURCE via drift) > FAIL_FUNCTIONAL >
    single-pid > duration > PASS (with sampling attributed in the note).
    """
    if ev.get("cancelled"):
        return ("CANCELLED",
                "runner stopped cleanly before the requested duration",
                ev.get("attribution") or "operator stop")
    if ev.get("infraError"):
        return ("INFRA_INTERRUPTED", ev["infraError"], ev["infraError"])

    inst = ev.get("instStream", "")
    ok_stream = ("OK (1 test)" in inst) and ("FAILURES!!!" not in inst)

    # A fixture device-side gate breach surfaces as its assertion message in the stream.
    drift = re.search(r"cumulative (FD|thread|PSS) drift:\s*(\d+)\s*->\s*(\d+)", inst)
    if drift:
        kind = drift.group(1)
        return ("FAIL_RESOURCE",
                f"{kind} peak gate breached: {drift.group(2)} -> {drift.group(3)} "
                f"(limit +{GATES[kind]})",
                "fixture device-side checkBounds assertion")

    if not ok_stream:
        if "FAILURES!!!" in inst:
            return ("FAIL_FUNCTIONAL",
                    "am-instrument stream reports FAILURES!!!",
                    "nested fixture failure (see instrumentation.log)")
        return ("FAIL_FUNCTIONAL",
                "am-instrument stream is not 'OK (1 test)'",
                "test did not report success")

    # Stream is green: the fixture's own per-cycle log is the authoritative process evidence.
    pids = ev.get("fixturePids") or ev.get("hostPids") or set()
    if len(pids) > 1:
        return ("FAIL_FUNCTIONAL",
                f"target process restarted during the soak (pids: {sorted(pids)})",
                "same-process invariant violated")
    if not pids:
        return ("INCONCLUSIVE",
                "no process-id evidence (HelixSoak pid= and pidof both empty); cannot confirm "
                "same-process for this soak",
                "single-process evidence missing")

    if ev.get("elapsedSec") is not None and ev.get("seconds") is not None \
            and ev["elapsedSec"] < ev["seconds"]:
        return ("INCONCLUSIVE",
                f"stream OK but host elapsed {ev['elapsedSec']:.0f}s < requested {ev['seconds']}s "
                "(the soak did not run the full requested duration)",
                "duration not fully elapsed")

    # Green stream + single pid + full duration: the requested soak fully and cleanly passed.
    preset = ev.get("preset", "pilot")
    seconds = ev.get("seconds")
    cycles = ev.get("cycles")
    samp = ev.get("sampling", {})
    total = samp.get("total", 0)
    ok = samp.get("ok", 0)
    if preset == "p24h":
        note = (f"24h same-process soak fully green (single pid, {cycles} cycles, "
                f"no gate drift; fixture device-side gates authoritative)")
    else:
        note = (f"{preset} ({seconds}s) fully green (single pid, {cycles} cycles, no gate drift); "
                f"HARNESS VALIDATION -- NOT a 24h result; requires --preset p24h for the EV-03 deliverable")
    if total and total != ok:
        note += (f"; host app-process sampling {ok}/{total} succeeded "
                 f"({total - ok} attributed failures in samples.jsonl) -- corroboration only, "
                 f"fixture device-side gate is authoritative")
    return ("PASS", note, "EV-03 resource soak" if preset == "p24h" else "harness validation")


class Soak:
    def __init__(self, args):
        self.args = args
        self.wl = WORKLOADS[args.workload]
        self.test_pkg = self.wl["component"].split("/", 1)[0]
        self.base = [args.adb, "-s", args.serial]
        self.out = args.output
        self.seconds = args.seconds
        self.observe = args.observe_breaches
        self.memory_sampler = args.memory_sampler
        self.cancelled = False
        self.started = 0.0
        self.identity = {}
        self.samples = []
        self.host_pids = set()
        signal.signal(signal.SIGTERM, self._on_signal)
        signal.signal(signal.SIGINT, self._on_signal)

    def _on_signal(self, signum, _frame):
        self.cancelled = True

    # ---- host shell helpers ------------------------------------------------
    def shell(self, *words, timeout=30):
        return subprocess.check_output(self.base + ["shell", *words], text=True,
                                       timeout=timeout).strip()

    def shell_ok(self, *words, timeout=20):
        """Best-effort read for sampling: returns stdout (stripped) or None on any failure.
        Never raises, so a sampling failure is attributed (recorded), not fatal to the run."""
        try:
            r = subprocess.run(self.base + ["shell", *words], text=True, timeout=timeout,
                               capture_output=True)
            return (r.stdout or "").strip() or None
        except Exception:
            return None

    # ---- §5.1 fixed artifact identity --------------------------------------
    def _installed_artifact(self, pkg):
        out = {"path": None, "sha256": None}
        raw = self.shell_ok("pm", "path", pkg)
        if raw:
            for line in raw.splitlines():
                if line.startswith("package:"):
                    path = line.removeprefix("package:").strip()
                    h = self.shell_ok("sha256sum", path)
                    out["path"] = path
                    out["sha256"] = h.split()[0] if h and h.split() else None
                    break
        return out

    def build_identity(self):
        api = self.shell("getprop", "ro.build.version.sdk").strip()
        ident = {
            "preset": self.args.preset, "runId": self.args.run_id, "serial": self.args.serial,
            "workload": self.args.workload, "seconds": self.seconds,
            "avd": self.shell_ok("getprop", "ro.boot.qemu.avd_name"),
            "api": api,
            "release": self.shell("getprop", "ro.build.version.release").strip(),
            "fingerprint": self.shell("getprop", "ro.build.fingerprint").strip(),
            "abi": self.shell("getprop", "ro.product.cpu.abi").strip(),
            "bootId": self.shell("cat", "/proc/sys/kernel/random/boot_id").strip(),
            "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
            "workingDiffSha256": hashlib.sha256(
                subprocess.check_output(["git", "diff", "HEAD"])).hexdigest(),
            "untrackedTestFiles": subprocess.check_output(
                ["git", "status", "--porcelain"], text=True).strip(),
            "package": self.wl["measure_pkg"], "testPackage": self.test_pkg,
            "component": self.wl["component"], "testClass": self.wl["test"],
            "observeBreaches": self.observe, "memorySampler": self.memory_sampler,
        }
        # Pin the installed artifacts (app process package + test package) by sha256 -- no install here.
        ident["installedArtifacts"] = {
            pkg: self._installed_artifact(pkg) for pkg in {self.wl["measure_pkg"], self.test_pkg}
        }
        memtotal = self.shell_ok("cat", "/proc/meminfo") or ""
        m = re.search(r"MemTotal:\s*(\d+) kB", memtotal)
        ident["ramKb"] = int(m.group(1)) if m else None
        usage = shutil.disk_usage("/")
        ident["freeDiskGbAtStart"] = round(usage.free / (1024 ** 3), 2)
        self.identity = ident
        return ident

    # ---- §5.2/3 host sampling (single-process + app-process PSS + attribution)
    def target_pid(self):
        raw = self.shell_ok("pidof", self.wl["measure_pkg"], timeout=15)
        if not raw:
            return None
        pids = raw.split()
        return int(pids[0]) if len(pids) == 1 else sorted(map(int, pids))[-1]

    def parse_pss(self, raw):
        for line in raw.splitlines():
            if re.match(r"\s*TOTAL\s", line):
                nums = re.findall(r"\d+", line.split("TOTAL", 1)[1])
                if nums:
                    return int(nums[0])
        return None

    def sample(self):
        """One host tick: app-process pid + PSS. Independent lens on the product process; the
        fixture's device-side gate is authoritative. Failures are attributed, never faked."""
        t0 = time.monotonic()
        rec = {"hostSec": round(time.monotonic() - self.started, 1),
               "ok": False, "pid": None, "appPssKb": None, "note": None}
        pid = self.target_pid()
        rec["pid"] = pid
        if pid is not None:
            self.host_pids.add(pid)
        if self.memory_sampler == "local" and pid is not None:
            raw = self.shell_ok("dumpsys", "meminfo", "--local", str(pid))
        else:
            raw = self.shell_ok("dumpsys", "meminfo", self.wl["measure_pkg"])
        if raw:
            pss = self.parse_pss(raw)
            rec["appPssKb"] = pss
            rec["ok"] = pss is not None
            if pss is None:
                rec["note"] = "dumpsys meminfo returned but no parseable TOTAL (PSS)"
        else:
            rec["note"] = ("app process not running (between cycles) or dumpsys meminfo failed"
                           if pid is None else "dumpsys meminfo failed/timed out")
        rec["sampleMs"] = int((time.monotonic() - t0) * 1000)
        self.samples.append(rec)
        with (self.out / "samples.jsonl").open("a") as f:
            f.write(json.dumps(rec) + "\n")

    # ---- forensics: stream-scan the (possibly multi-GB) logcat, memory-safe --
    def scan_logcat(self):
        res = {"fixturePids": set(), "cycles": None, "fixturePeakFd": None,
               "fixturePeakThreads": None, "fixturePeakPssKb": None, "nHelixSoak": 0,
               "testRunnerFinished": False, "failureMsgs": []}
        path = self.out / "logcat.log"
        if not path.exists():
            return res
        cycle_re = re.compile(r"pid=(\d+) cycles=(\d+) elapsedMs=\d+ fd=(\d+) threads=(\d+) pssKb=(\d+)")
        with path.open() as f:
            for line in f:
                if "HelixSoak:" in line:
                    m = cycle_re.search(line)
                    if m:
                        res["nHelixSoak"] += 1
                        res["fixturePids"].add(int(m.group(1)))
                        res["cycles"] = int(m.group(2))
                        res["fixturePeakFd"] = max(res["fixturePeakFd"] or 0, int(m.group(3)))
                        res["fixturePeakThreads"] = max(res["fixturePeakThreads"] or 0, int(m.group(4)))
                        res["fixturePeakPssKb"] = max(res["fixturePeakPssKb"] or 0, int(m.group(5)))
                elif "TestRunner:" in line and re.search(r"run finished:\s*1 tests,\s*0 failed", line):
                    res["testRunnerFinished"] = True
                elif "FAILURES!!!" in line or "AssertionError" in line or "drift:" in line:
                    if len(res["failureMsgs"]) < 20:
                        res["failureMsgs"].append(line.strip()[:300])
        return res

    # ---- run ---------------------------------------------------------------
    def run(self):
        self.build_identity()
        self.out.mkdir(parents=True, exist_ok=True)
        (self.out / "manifest.json").write_text(json.dumps(
            {**self.identity, "state": "RUNNING",
             "startUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}, indent=2))

        cat_fh = (self.out / "logcat.log").open("w")
        collector = subprocess.Popen(self.base + ["logcat", "-v", "threadtime", "-T", "1"],
                                     stdout=cat_fh, stderr=subprocess.STDOUT)
        inst_fh = (self.out / "instrumentation.log").open("w")
        runner = subprocess.Popen(
            self.base + ["shell", "am", "instrument", "-w", "-r",
                         "-e", "class", self.wl["test"],
                         "-e", "helix.soak.seconds", str(self.seconds),
                         "-e", "helix.soak.observe", str(self.observe).lower(),
                         self.wl["component"]],
            stdout=inst_fh, stderr=subprocess.STDOUT)

        self.started = time.monotonic()
        last = self.started
        infra = None
        try:
            while runner.poll() is None:
                if self.cancelled:
                    break
                time.sleep(SAMPLING_INTERVAL)
                now = time.monotonic()
                if now - last > WATCHDOG_GAP_MAX:
                    raise RuntimeError(
                        f"host suspended / sampling gap {now - last:.0f}s > {WATCHDOG_GAP_MAX}s")
                last = now
                if now - self.started > self.seconds + TIMEOUT_SLACK:
                    raise TimeoutError(
                        f"instrumentation exceeded {self.seconds}+{TIMEOUT_SLACK}s without finishing")
                self.sample()
        except BaseException as err:
            infra = f"{type(err).__name__}: {err}"
        finally:
            elapsed = time.monotonic() - self.started
            if runner.poll() is None:
                runner.terminate()
                subprocess.run(self.base + ["shell", "am", "force-stop", self.wl["measure_pkg"]],
                               timeout=30, check=False)
                try:
                    runner.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    runner.kill()
            try:
                inst_fh.close()
            except Exception:
                pass
            collector.terminate()
            try:
                collector.wait(timeout=15)
            except subprocess.TimeoutExpired:
                collector.kill()
            try:
                cat_fh.close()
            except Exception:
                pass
            self.finalize(elapsed, infra, (self.out / "instrumentation.log"))
        return 0

    # ---- §5.4 forensics-first finalize -------------------------------------
    def finalize(self, elapsed, infra, inst_path):
        inst_stream = ""
        try:
            inst_stream = inst_path.read_text()
        except Exception:
            inst_stream = ""
        lc = self.scan_logcat()
        ev = {
            "cancelled": self.cancelled,
            "infraError": infra,
            "instStream": inst_stream,
            "seconds": self.seconds,
            "elapsedSec": round(elapsed, 1),
            "preset": self.args.preset,
            "fixturePids": lc["fixturePids"],
            "hostPids": self.host_pids,
            "cycles": lc["cycles"],
            "sampling": {
                "total": len(self.samples),
                "ok": sum(1 for s in self.samples if s["ok"]),
                "failed": sum(1 for s in self.samples if not s["ok"]),
            },
            "testRunnerFinished": lc["testRunnerFinished"],
        }
        state, note, attribution = classify(ev)
        eff_pids = lc["fixturePids"] or self.host_pids
        result = {
            **self.identity,
            "state": state, "note": note, "attribution": attribution,
            "elapsedSeconds": round(elapsed, 1),
            "cycles": lc["cycles"],
            "baselineWarmupCycles": BASELINE_WARMUP_CYCLES,
            "casesPerCycle": CASES_PER_CYCLE,
            "totalNestedCases": ((lc["cycles"] or 0) + BASELINE_WARMUP_CYCLES) * CASES_PER_CYCLE,
            "singlePid": len(eff_pids) == 1,
            "fixturePids": sorted(lc["fixturePids"]),
            "hostPids": sorted(self.host_pids),
            "processModelMatches": bool(
                lc["fixturePids"] and self.host_pids and lc["fixturePids"] == self.host_pids),
            "fixturePeak": {
                "fd": lc["fixturePeakFd"], "threads": lc["fixturePeakThreads"],
                "pssKb": lc["fixturePeakPssKb"],
            },
            "testRunnerFinished": lc["testRunnerFinished"],
            "sampling": ev["sampling"],
            "failureMsgs": lc["failureMsgs"],
            "endUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        (self.out / "result.json").write_text(json.dumps(result, indent=2))
        print(json.dumps(result))
        return 0 if state == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("serial", help="dedicated emulator serial (emulator-NNNN)")
    parser.add_argument("--workload", choices=["app", "browser"], default="app")
    parser.add_argument("--preset", choices=list(PRESETS), default="pilot",
                        help="pilot=900s / precheck=7200s / p24h=86400s (default pilot)")
    parser.add_argument("--seconds", type=int, default=None,
                        help="override the preset duration (60..86400)")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-id", default=None)
    parser.add_argument("--memory-sampler", choices=["full", "local"], default="full",
                        help="full=dumpsys meminfo <pkg>; local=dumpsys meminfo --local <pid>")
    parser.add_argument("--observe-breaches", action="store_true",
                        help="app only: run the full duration then fail if any peak breached "
                             "(default is fail-fast on the first breach)")
    parser.add_argument("--adb", default=str(
        Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
        / "platform-tools/adb"))
    args = parser.parse_args()

    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("requires an explicit dedicated emulator serial (emulator-NNNN)")
    if args.observe_breaches and args.workload != "app":
        parser.error("--observe-breaches is available only for the app workload")
    seconds = args.seconds if args.seconds is not None else PRESETS[args.preset]
    if not 60 <= seconds <= 86400:
        parser.error("--seconds must be in 60..86400")
    if args.output.exists():
        parser.error(f"output dir already exists: {args.output} (a prior attempt lives here)")
    args.seconds = seconds
    if not args.run_id:
        args.run_id = f"ev03-{args.workload}-{args.preset}-{time.strftime('%Y%m%d-%H%M%S', time.gmtime())}"

    soak = Soak(args)
    state_rc = soak.run()
    return state_rc


if __name__ == "__main__":
    raise SystemExit(main())

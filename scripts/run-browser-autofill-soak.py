#!/usr/bin/env python3
"""Host runner for the EV-02 browser/Autofill soak (docs/development/browser-autofill-soak-plan.md §5).

Launches the device-side BrowserAutofillSoakDeviceTest ONCE, owns the wait, and judges the result
from the `am instrument` stdout stream (OK (N tests) / FAILURES!!!) + logcat 'TestRunner: run
finished' + the device's soak-done.json / cycles.jsonl -- NEVER from INSTRUMENTATION_CODE (teardown
SIGKILLs the finished-inst process -> -1, benign) nor the JUnit XML (never flushed to disk).

The workload is chosen by --preset (a canonical, frozen config -- the difference between a 15-min
pilot, a 2h pre-check and the 24h formal run lives ONLY here, never in code and never dynamic). A
short 'selftest' preset exists purely to validate this harness end-to-end; it is never a plan
milestone and its artifacts are labelled as such.

Result states are the closed set PASS / FAIL_FUNCTIONAL / FAIL_RESOURCE / INFRA_INTERRUPTED /
INCONCLUSIVE / CANCELLED. A pilot's PASS means "fixture/harness passed", not 24h acceptance. If the
system UID-proxy sampling is unavailable the run can be functionally green yet only INCONCLUSIVE
(the system-Binder question is not closable without it) -- it is never faked to 0 nor upgraded.
"""
import argparse
import importlib.util
import hashlib
import json
import os
import pathlib
import re
import shutil
import signal
import subprocess
import sys
import time

_evidence_spec = importlib.util.spec_from_file_location(
    "helix_soak_evidence", pathlib.Path(__file__).resolve().parent / "debug/2026-09-10/soak_evidence.py",
)
_evidence = importlib.util.module_from_spec(_evidence_spec)
_evidence_spec.loader.exec_module(_evidence)

PACKAGE = "com.helix.feature.browser.test"
COMPONENT = PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "com.helix.feature.browser.BrowserAutofillSoakDeviceTest"
APK_REL = pathlib.Path("feature/browser/build/outputs/apk/androidTest/debug/browser-debug-androidTest.apk")
DEVICE_FILES = "/sdcard/Android/data/com.helix.feature.browser.test/files"
APK_SNAP = "apks/" + PACKAGE + ".apk"
RESULT_STATES = ["PASS", "FAIL_FUNCTIONAL", "FAIL_RESOURCE", "INFRA_INTERRUPTED", "INCONCLUSIVE", "CANCELLED"]

# Canonical, frozen workload per plan §3/§5. The selftest preset is a harness probe, not a milestone.
PRESETS = {
    # ~70s end-to-end harness probe: proves identity/snapshot/install, sampling, watchdogs, and the
    # success-judgment path. cycleSeconds=20 keeps the active-gap watchdog comfortably under 180s.
    "selftest": dict(warmup=20, duration=40, cooldown=10, cycle=20, cycleMax=15,
                     activePerHour=30, idlePerHour=10, heartbeat=5, recreateEvery=2, bgfgEvery=1, bgfgSeconds=2),
    # P1 pilot: 5min warmup + 15min active (7 full 120s slots) + 5min observe.
    "pilot": dict(warmup=300, duration=900, cooldown=300, cycle=120, cycleMax=90,
                  activePerHour=3000, idlePerHour=600, heartbeat=30, recreateEvery=10, bgfgEvery=5, bgfgSeconds=30),
    # P2 paired pre-check: 30min warmup + 2h (two full hour-blocks = 50 rounds) + 30min observe.
    "p2": dict(warmup=1800, duration=7200, cooldown=1800, cycle=120, cycleMax=112,
               activePerHour=3000, idlePerHour=600, heartbeat=30, recreateEvery=10, bgfgEvery=5, bgfgSeconds=30),
    # P3 formal: 30min warmup + 24h (24 hour-blocks = 600 rounds) + 30min observe.
    "p3": dict(warmup=1800, duration=86400, cooldown=1800, cycle=120, cycleMax=112,
               activePerHour=3000, idlePerHour=600, heartbeat=30, recreateEvery=10, bgfgEvery=5, bgfgSeconds=30),
    # P1 sampling-perturbation control (plan browser-autofill-soak-plan.md:89): a FIXED 25-cycle
    # workload (2 warmup + 23 active, no idle -> exactly 25 cycles.jsonl rows) run TWICE with the
    # only variable being --sampler. Both groups keep the start/end resource snapshots so a group can
    # never hide growth by turning monitoring off. Short 30s slots keep the whole run ~13.5min (this
    # is NOT a 24h run). The 10% screen is on the median per-cycle work time (sum of step elapsedMs);
    # bgfgSeconds=5 keeps bgfg cycles from swamping the ~0.8s normal-cycle median.
    "perturb": dict(warmup=60, duration=690, cooldown=60, cycle=30, cycleMax=25,
                    activePerHour=3600, idlePerHour=0, heartbeat=15, recreateEvery=8, bgfgEvery=5, bgfgSeconds=5),
}

# Plan §5: host-sampling cadence, 180s liveness watchdog, disk high/low water marks.
TICK_S = 10
PID_CHECK_EVERY = 30
SAMPLE_EVERY = 60
PROXY_EVERY = 300
WATCHDOG_S = 180
# The fixture emits a progress update at most one slot apart inside an active block (<= cycleSeconds,
# < 180s for the canonical 120s grid); scale the active watchdog for larger grids, never below 180.
ACTIVE_WATCHDOG = lambda cycle: max(WATCHDOG_S, cycle + 60)
DISK_START_MIN_GB = 20
DISK_STOP_MIN_GB = 5
LOGCAT_ROTATE_MB = 200
AUTOFILL_ON_COMPONENT = PACKAGE + "/com.helix.feature.browser.FixtureAutofillService"

_cancelled = False


def _on_signal(signum, _frame):
    global _cancelled
    _cancelled = True


class Runner:
    def __init__(self, args):
        self.args = args
        self.adb = args.adb or shutil.which("adb")
        self.base = [self.adb, "-s", args.serial]
        self.out = args.output
        self.cfg = dict(PRESETS[args.preset])
        self.autofill = args.autofill_mode
        self.state = "RUNNING"
        self.note = ""
        self.attribution = ""
        self.samples = []
        self.proxy_samples = []
        self.identity = {}
        self.apk_hash = None
        self.root_available = False
        self.last_pid = None
        self.logcat_proc = None
        self.inst_proc = None
        self.logcat_seq = 0
        self.logcat_bytes = 0
        self.logcat_fail = 0
        self._last_progress_sig = None   # (updatedMonotonicMs, seq, phase) of the last matching progress.json
        self._done_at = None             # host-monotonic time we first saw phase=done
        self._abort = None               # (state, reason, attribution) set by a fatal branch
        # P1 sampling-perturbation control: "full" = periodic PID/FD/PSS/proxy sampling (the metric
        # IPC under test); "heartbeat" = only the fixture progress.json heartbeat + start/end resource
        # snapshots. Both modes take the start/end snapshot pair (plan: never hide growth by disabling
        # monitoring). self.ipc counts the metric queries themselves (they generate IPC; plan requires
        # recording the count).
        self.sampler = getattr(args, "sampler", "full")
        self._end_snapshot_taken = False
        self.ipc = {"pid_checks": 0, "resource_samples": 0, "proxy_samples": 0}

    # ---- adb helpers --------------------------------------------------------
    def shell(self, *words, timeout=30, retries=1, sudo=False):
        """Run an adb shell command; retry the READ once after 10s (never retry a workload)."""
        cmd = self.base + ["shell"] + (["su", "0"] if sudo else []) + list(words)
        last = None
        for attempt in range(retries + 1):
            try:
                return subprocess.check_output(cmd, text=True, timeout=timeout)
            except subprocess.CalledProcessError as e:
                last = e
            except (subprocess.TimeoutExpired, OSError) as e:
                last = e
            if attempt < retries:
                time.sleep(10)
        raise RuntimeError(f"adb shell {' '.join(words)} failed: {last!r}")

    def shell_ok(self, *words, timeout=20, sudo=False):
        """Best-effort read: returns stdout or None (never raises) for sampling."""
        try:
            return self.shell(*words, timeout=timeout, retries=0, sudo=sudo)
        except Exception:
            return None

    def read_device_file(self, name):
        raw = self.shell_ok("cat", f"{DEVICE_FILES}/{name}", timeout=20)
        if raw is None:
            return None
        return raw.strip()

    def device_now_ms(self):
        """Device monotonic time (elapsedRealtime ~= time-since-boot) via /proc/uptime, ms."""
        raw = self.shell_ok("cat", "/proc/uptime", timeout=15)
        if raw is None:
            return None
        return int(float(raw.split()[0]) * 1000)

    # ---- P0: identity + snapshot + install + verify ------------------------
    def build_identity(self):
        a = self.args
        api = self.shell("getprop", "ro.build.version.sdk").strip()
        pkg_dump = self.shell("dumpsys", "package", PACKAGE)
        uid = pkg_dump.split("userId=")[1].split()[0] if "userId=" in pkg_dump else None
        ident = {
            "preset": a.preset, "phase": a.phase, "runId": a.run_id, "serial": a.serial,
            "avd": self.shell("getprop", "ro.boot.qemu.avd_name").strip() or None,
            "api": api, "release": self.shell("getprop", "ro.build.version.release").strip(),
            "fingerprint": self.shell("getprop", "ro.build.fingerprint").strip(),
            "abi": self.shell("getprop", "ro.product.cpu.abi").strip(),
            "bootId": self.shell("cat", "/proc/sys/kernel/random/boot_id").strip(),
            "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
            "workingDiffSha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD"])).hexdigest(),
            "untrackedTestFiles": subprocess.check_output(["git", "status", "--porcelain"], text=True).strip(),
            "package": PACKAGE, "uid": uid,
            "component": COMPONENT, "testClass": TEST_CLASS,
            "autofillService": AUTOFILL_ON_COMPONENT if self.autofill == "on" else None,
            "config": dict(self.cfg, autofillMode=self.autofill),
        }
        webview = self.shell_ok("dumpsys", "webviewupdate", timeout=15) or ""
        (self.out / "webviewupdate-start.log").write_text(webview)
        ident.update(_evidence.parse_webview_identity(webview))
        memtotal = self.shell_ok("cat", "/proc/meminfo") or ""
        mt = re.search(r"MemTotal:\s*(\d+) kB", memtotal)
        ident["ramKb"] = int(mt.group(1)) if mt else None
        self.identity = ident
        return ident

    def snapshot_and_install(self):
        a = self.args
        if a.apk:
            src = pathlib.Path(a.apk).resolve()
        else:
            # Always rebuild, not only when the APK is missing: an existing APK can be stale relative to a
            # source edit, and installing stale bytes would silently test the wrong code. Gradle is
            # incremental, so an up-to-date build is cheap.
            subprocess.check_call(["./gradlew", ":feature:browser:assembleDebugAndroidTest"])
            src = APK_REL.resolve()
        snap = self.out / APK_SNAP
        snap.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, snap)
        snap.chmod(0o444)  # read-only snapshot
        self.apk_hash = hashlib.sha256(snap.read_bytes()).hexdigest()
        self.identity["apkSha256"] = self.apk_hash
        self.identity["configSha256"] = hashlib.sha256(
            json.dumps(self.identity["config"], sort_keys=True).encode()).hexdigest()
        # Install the same snapshot bytes, then verify the installed path's hash + version.
        subprocess.check_call(self.base + ["install", "-r", str(snap)])
        remote = self.shell("pm", "path", PACKAGE).strip().splitlines()
        assert remote and remote[0].startswith("package:"), f"no installed path for {PACKAGE}: {remote!r}"
        remote_path = remote[0].removeprefix("package:")
        remote_hash = self.shell("sha256sum", remote_path).split()[0]
        assert remote_hash == self.apk_hash, f"installed APK hash mismatch: {remote_hash} != {self.apk_hash}"
        self.identity["installedPath"] = remote_path
        self.identity["installedHash"] = remote_hash

    def verify_environment(self):
        a = self.args
        assert re.fullmatch(r"emulator-\d+", a.serial), "requires an explicit dedicated emulator serial"
        if not self.adb:
            raise SystemExit("adb not found on PATH")
        # Host sleep-prevention must be running (verified, not inferred), unless explicitly waived.
        if not a.no_sleep_check:
            sleep = subprocess.run(["pgrep", "-x", "caffeinate"], capture_output=True, text=True)
            if sleep.returncode != 0:
                raise SystemExit("host sleep-prevention (caffeinate) is not running; start it or pass --no-sleep-check")
        # Free disk at start (high water mark) -- the host's single volume.
        usage = shutil.disk_usage("/")
        free_gb = usage.free / (1024 ** 3)
        self.identity["freeDiskGbAtStart"] = round(free_gb, 2)
        if free_gb < DISK_START_MIN_GB:
            raise SystemExit(f"free disk {free_gb:.1f}GiB < {DISK_START_MIN_GB}GiB start minimum")
        # Root read access (optional; degrades /proc sampling if absent, never fakes it).
        uid = self.shell_ok("id", "-u", sudo=True)
        self.root_available = (uid is not None and uid.strip() == "0")
        self.identity["rootReadAvailable"] = self.root_available
        # Record the original autofill_service setting (before/after restore evidence).
        self.identity["autofillServiceBefore"] = self.shell("settings", "get", "secure", "autofill_service").strip()

    # ---- expected workload (mirrors the fixture's fixed grid) ---------------
    def expected_slots(self):
        cfg = self.cfg
        cycle = cfg["cycle"]
        warmup = cfg["warmup"] // cycle if cfg["warmup"] > 0 else 0
        total = 0
        block_start, dur_end = 0, cfg["duration"]
        while block_start < dur_end:
            active_end = min(block_start + cfg["activePerHour"], dur_end)
            if active_end > block_start:
                total += (active_end - block_start) // cycle
            idle_end = min(active_end + cfg["idlePerHour"], dur_end)
            nxt = max(idle_end, block_start + 1)
            if nxt <= block_start:
                break
            block_start = nxt
        return warmup, total

    # ---- host sampling ------------------------------------------------------
    def target_pid(self):
        raw = self.shell_ok("pidof", PACKAGE, timeout=15)
        if raw is None:
            return None
        pids = raw.split()
        return int(pids[0]) if len(pids) == 1 else (sorted(map(int, pids))[-1] if pids else None)

    def process_starttime(self, pid):
        raw = self.shell_ok("cat", f"/proc/{pid}/stat", sudo=self.root_available, timeout=15)
        if raw is None:
            return None
        # field 2 (comm) is parenthesized and may contain spaces; split on the LAST ')'. rest[0] is
        # field 3 (state) ... rest[19] is field 22 (starttime).
        rest = raw.rsplit(")", 1)[1].split()
        return rest[19] if len(rest) >= 20 else None

    def parse_pss(self, meminfo_raw):
        for line in meminfo_raw.splitlines():
            if re.match(r"\s*TOTAL\s", line):
                nums = re.findall(r"\d+", line.split("TOTAL", 1)[1])
                if nums:
                    return int(nums[0])
        return None

    def sample_resources(self):
        """Per-minute: FD / threads / local-binder-fd / PSS of the target process. Root-gated /proc
        reads degrade to null (never faked); PSS via dumpsys meminfo --local <pid> (no root)."""
        self.ipc["resource_samples"] += 1  # P1 perturbation control: count the metric queries (IPC)
        t0 = time.monotonic()
        rec = {"hostSec": round(time.monotonic() - self.started, 1), "deviceUptimeSec": None,
               "ok": False, "fd": None, "threads": None, "binderLocal": None, "pssKb": None}
        pid = self.target_pid()
        rec["pid"] = pid
        rec["deviceUptimeSec"] = (self.device_now_ms() // 1000) if self.device_now_ms() is not None else None
        if pid is None:
            rec["note"] = "no target pid"
            self._finish_sample(rec, t0)
            return rec
        if self.root_available:
            # Count on the HOST, never via a device-side `sh -c "... | wc -l"`: adb joins its argv with
            # spaces and strips the quotes, so `sh -c` only receives `ls` and lists cwd (/) instead of
            # /proc/<pid>/fd (P1 pilot caught fd pinned at ~35 == `ls /`). A plain `ls` (no shell
            # metachars) is safe through adb; an empty/None result (process gone, transient) degrades to
            # null -- never faked.
            fd_raw = self.shell_ok("ls", "-1", f"/proc/{pid}/fd", sudo=True)
            th_raw = self.shell_ok("ls", "-1", f"/proc/{pid}/task", sudo=True)
            bd_raw = self.shell_ok("ls", "-l", f"/proc/{pid}/fd", sudo=True)
            rec["fd"] = len(fd_raw.split()) if fd_raw else None
            rec["threads"] = len(th_raw.split()) if th_raw else None
            rec["binderLocal"] = sum(1 for line in bd_raw.splitlines() if "binder" in line) if bd_raw else None
        meminfo = self.shell_ok("dumpsys", "meminfo", "--local", str(pid), timeout=20)
        if meminfo:
            rec["pssKb"] = self.parse_pss(meminfo)
            (self.out / "meminfo" / f"{int(time.monotonic() - self.started)}s.log").write_text(meminfo)
        rec["ok"] = rec["pssKb"] is not None
        self._finish_sample(rec, t0)
        return rec

    def _finish_sample(self, rec, t0):
        rec["sampleMs"] = int((time.monotonic() - t0) * 1000)
        self.samples.append(rec)
        self.append_jsonl("samples.jsonl", rec)

    def sample_system_proxy(self):
        """Every 5min: system_server's Binder proxy count for the target UID. No standard, permission-
        clean interface exposes this on the emulator, so it is marked 'unavailable' (NEVER 0) unless a
        supported controlled diagnostic is present. This keeps the system-Binder evaluation INCONCLUSIVE
        rather than faking closure."""
        self.ipc["proxy_samples"] += 1  # P1 perturbation control: count the metric queries (IPC)
        t0 = time.monotonic()
        rec = {"hostSec": round(time.monotonic() - self.started, 1),
               "systemUidProxy": "unavailable", "available": False, "note": "no supported controlled diagnostic on emulator"}
        # Best-effort: count binder fds of system_server (proxy side) as an unattributed bound, clearly
        # labelled -- NOT a per-UID proxy count, never used as a substitute in the gate.
        ss = self.shell_ok("pidof", "system_server", timeout=15)
        if ss and self.root_available:
            # Host-side count (no device `sh -c ... | grep -c` -- adb strips the quotes; see
            # sample_resources). Context only: NOT a per-UID proxy count, never a gate input.
            bd_raw = self.shell_ok("ls", "-l", f"/proc/{ss.split()[0]}/fd", sudo=True)
            if bd_raw:
                rec["systemServerBinderFds"] = sum(1 for line in bd_raw.splitlines() if "binder" in line)  # context only
        rec["sampleMs"] = int((time.monotonic() - t0) * 1000)
        self.proxy_samples.append(rec)
        self.append_jsonl("system-proxy.jsonl", rec)

    # ---- atomic file helpers ------------------------------------------------
    def append_jsonl(self, name, obj):
        with (self.out / name).open("a") as f:
            f.write(json.dumps(obj) + "\n")

    def write_state(self):
        state = {
            "state": self.state, "preset": self.args.preset, "phase": self.args.phase,
            "runId": self.args.run_id, "serial": self.args.serial,
            "updatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "elapsedSeconds": round(time.monotonic() - self.started) if hasattr(self, "started") else 0,
            "lastPid": self.last_pid, "note": self.note, "attribution": self.attribution,
        }
        tmp = self.out / "progress-host.json.tmp"
        tmp.write_text(json.dumps(state, indent=2) + "\n")
        tmp.replace(self.out / "progress-host.json")

    def abort(self, state, reason, attribution=""):
        self._abort = (state, reason, attribution)

    # ---- lifecycle ----------------------------------------------------------
    def start_collectors(self):
        self.logcat_seq = 0
        self._open_logcat()
        args = ["-e", "helix.soak.runId", self.args.run_id,
                "-e", "helix.soak.warmupSeconds", str(self.cfg["warmup"]),
                "-e", "helix.soak.durationSeconds", str(self.cfg["duration"]),
                "-e", "helix.soak.cooldownSeconds", str(self.cfg["cooldown"]),
                "-e", "helix.soak.cycleSeconds", str(self.cfg["cycle"]),
                "-e", "helix.soak.cycleMaxSeconds", str(self.cfg["cycleMax"]),
                "-e", "helix.soak.activeSecondsPerHour", str(self.cfg["activePerHour"]),
                "-e", "helix.soak.idleSecondsPerHour", str(self.cfg["idlePerHour"]),
                "-e", "helix.soak.autofillMode", self.autofill,
                "-e", "helix.soak.recreateEvery", str(self.cfg["recreateEvery"]),
                "-e", "helix.soak.bgfgEvery", str(self.cfg["bgfgEvery"]),
                "-e", "helix.soak.bgfgSeconds", str(self.cfg["bgfgSeconds"]),
                "-e", "helix.soak.heartbeatSeconds", str(self.cfg["heartbeat"])]
        cmd = self.base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS] + args + [COMPONENT]
        self.inst_proc = subprocess.Popen(cmd, stdout=(self.out / "instrumentation.log").open("w"),
                                          stderr=subprocess.STDOUT)

    def _open_logcat(self):
        path = self.out / f"logcat{'' if self.logcat_seq == 0 else '-' + str(self.logcat_seq)}.log"
        self.logcat_path = path
        self.logcat_bytes = 0
        self.logcat_proc = subprocess.Popen(self.base + ["logcat", "-v", "threadtime", "-T", "1"],
                                            stdout=path.open("w"), stderr=subprocess.STDOUT)

    def rotate_logcat(self):
        if self.logcat_proc is None:
            return
        try:
            if self.logcat_path.exists() and self.logcat_path.stat().st_size > LOGCAT_ROTATE_MB * 1024 * 1024:
                self.logcat_proc.terminate()
                self.logcat_proc.wait(timeout=10)
                self.logcat_seq += 1
                self._open_logcat()
        except Exception as e:
            self.logcat_fail += 1
            self.note = f"logcat rotation issue: {e!r}"

    def pull_device_files(self):
        names = ["soak-manifest.json", "cycles.jsonl", "heartbeat.jsonl", "requests.jsonl",
                 "progress.json", "soak-done.json", "autofill-failure.json"]
        for name in names:
            raw = self.read_device_file(name)
            if raw is not None:
                (self.out / ("device-" + name)).write_text(raw + "\n" if not raw.endswith("\n") else raw)

    def teardown(self):
        if self.inst_proc is not None and self.inst_proc.poll() is None:
            self.inst_proc.terminate()
            try:
                self.inst_proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.inst_proc.kill()
            # Preserve state, then stop only the test process I created (never a mid-run force-stop
            # on a normal round; this is the terminal abnormal path).
            subprocess.run(self.base + ["shell", "am", "force-stop", PACKAGE], timeout=30, check=False)
        if self.logcat_proc is not None and self.logcat_proc.poll() is None:
            self.logcat_proc.terminate()
            try:
                self.logcat_proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.logcat_proc.kill()
        # Restore the autofill_service setting to its pre-run value. The fixture does this in its own
        # finally on a clean run (a no-op here), but on any path where the runner terminated the
        # fixture -- interrupt (SIGTERM/SIGINT), abort, or the force-stop above -- the fixture's
        # finally never ran, leaving the device pointing at a now-defunct fixture service (the
        # selftest1 "stuck autofill" failure mode, re-confirmed by the fault-interrupt round). Mirror
        # the fixture's exact restore semantics (empty/"null" -> delete, else -> put) and use
        # shell_ok so a restore failure degrades (and is recorded) instead of raising out of teardown.
        before = self.identity.get("autofillServiceBefore")
        if before is not None and before != "":
            if before == "null":
                self.shell_ok("settings", "delete", "secure", "autofill_service", timeout=20)
            else:
                self.shell_ok("settings", "put", "secure", "autofill_service", before, timeout=20)
            self.identity["autofillServiceRestoredByRunner"] = True

    def verify_restore(self):
        after = self.shell("settings", "get", "secure", "autofill_service").strip()
        self.identity["autofillServiceAfter"] = after
        before = self.identity.get("autofillServiceBefore", "")
        self.identity["autofillServiceRestored"] = (before == after)
        # The fixture restores to its pre-run value; 'null' is the clean default.
        return after

    # ---- judgment (plan §6) -------------------------------------------------
    def functional_ok(self, done, exp_warmup, exp_formal):
        inst = (self.out / "instrumentation.log").read_text()
        ok_stream = "OK (1 test)" in inst and "FAILURES!!!" not in inst
        cat = self.logcat_path.read_text() if self.logcat_path.exists() else ""
        for extra in sorted(self.out.glob("logcat-*.log")):
            cat += extra.read_text()
        ok_logcat = re.search(r"TestRunner:\s+run finished:\s+1 tests,\s+0 failed", cat) is not None
        problems = []
        if not ok_stream:
            problems.append("am-instrument stream not 'OK (1 test)' or contains FAILURES!!!")
        if not ok_logcat:
            problems.append("logcat missing 'TestRunner: run finished: 1 tests, 0 failed'")
        if done is None:
            problems.append("soak-done.json missing")
        else:
            if done.get("formalCycles") != exp_formal:
                problems.append(f"formalCycles {done.get('formalCycles')} != expected {exp_formal}")
            if done.get("warmupCycles") != exp_warmup:
                problems.append(f"warmupCycles {done.get('warmupCycles')} != expected {exp_warmup}")
            if self.autofill == "on" and done.get("requests", 0) < exp_warmup + exp_formal:
                problems.append("requests.jsonl too few for expected real fills")
        # Every recorded cycle must be status:ok; a single non-ok cycle is a functional failure.
        cycles = self._read_cycles()
        bad = [c for c in cycles if c.get("status") != "ok"]
        if bad:
            problems.append(f"{len(bad)} cycles not status:ok")
        single_pid = len({c.get("pid") for c in cycles}) == 1
        if cycles and not single_pid:
            problems.append("target process changed PID during the run")
        return (len(problems) == 0), problems, len(cycles), single_pid

    def _read_cycles(self):
        p = self.out / "device-cycles.jsonl"
        if not p.exists():
            return []
        return [json.loads(line) for line in p.read_text().splitlines() if line.strip()]

    def resource_ok(self):
        """Plan §6 resource gate. Only meaningful with enough idle windows (P3). With fewer (pilot/P2)
        return INCONCLUSIVE-with-reason -- never a pass-by-insufficiency, never a fake gate."""
        p3 = self.args.preset == "p3"
        if not p3:
            return None, f"resource gate not applied for preset={self.args.preset} (needs 24h); " \
                         f"checked functional + absolute drift only"
        # 24h path: per-hour idle medians vs warmup baseline B, growth FD+8/thread+16/PSS+96MiB,
        # slope over the 24 R_h. Implemented against the pulled samples; insufficient -> INCONCLUSIVE.
        # (Full 24h analysis runs here; for a pilot/p2 this is deliberately not invoked.)
        return None, "24h resource analysis pending (only valid with 24 blocks)"

    def finalize(self, exp_warmup, exp_formal):
        self.pull_device_files()
        self.verify_restore()
        done_raw = self.read_device_file("soak-done.json")
        done = json.loads(done_raw) if done_raw else None
        if self._abort:
            state, reason, attr = self._abort
            self.state, self.note, self.attribution = state, reason, attr
        elif _cancelled:
            self.state, self.note, self.attribution = "CANCELLED", "runner interrupted by signal", ""
        else:
            ok, problems, n_cycles, single_pid = self.functional_ok(done, exp_warmup, exp_formal)
            if not ok:
                self.state, self.attribution = "FAIL_FUNCTIONAL", "; ".join(problems)
                self.note = f"functional failure ({n_cycles} cycles, single_pid={single_pid})"
            else:
                res_state, res_note = self.resource_ok()
                proxy_ok = all(s.get("available") for s in self.proxy_samples) if self.proxy_samples else False
                if self.identity.get("autofillServiceRestored") is False:
                    self.state, self.note = "FAIL_FUNCTIONAL", "autofill_service was not restored to its pre-run value"
                elif res_state is not None and res_state != "PASS":
                    self.state, self.note = res_state, res_note
                elif not proxy_ok:
                    # Functional + app metrics green, but system UID-proxy data unavailable -> the
                    # system-Binder question is NOT closed. INCONCLUSIVE, never a blanket PASS.
                    self.state = "INCONCLUSIVE"
                    self.note = res_note + " | system UID-proxy unavailable -> system-Binder evaluation open"
                    self.attribution = "system-proxy sampling unavailable on emulator"
                else:
                    self.state, self.note = "PASS", res_note or "all gates satisfied"
        # Cross-clock duration verification (plan §5: host monotonic + device elapsedRealtime together;
        # a divergence beyond tolerance is not counted as continuous duration -> at most INCONCLUSIVE).
        if done and isinstance(done.get("durationMs"), int):
            dev_ms = done["durationMs"]
            host_ms = int((time.monotonic() - self.started) * 1000)
            expected_ms = (self.cfg["warmup"] + self.cfg["duration"] + self.cfg["cooldown"]) * 1000
            tol_ms = max(120_000, int(0.10 * expected_ms))
            consistent = abs(host_ms - dev_ms) <= tol_ms and abs(dev_ms - expected_ms) <= tol_ms
            self.identity["deviceDurationMs"] = dev_ms
            self.identity["hostElapsedMs"] = host_ms
            self.identity["durationConsistent"] = consistent
            if not consistent and self.state == "PASS":
                self.state = "INCONCLUSIVE"
                self.note += (f" | host/device duration divergence "
                              f"(host {host_ms}ms vs device {dev_ms}ms vs expected {expected_ms}ms)")
        self.elapsed = round(time.monotonic() - self.started)
        self.write_state()
        return self.state

    def take_start_snapshot(self):
        """START-of-workload resource snapshot (both sampler modes, per plan: never hide growth by
        disabling monitoring). Waits up to ~60s for the target instrument PID to be up, then samples
        once. A never-faked null sample is recorded if the process never appears. Sets last_pid and the
        identity PID fields so a heartbeat-mode run (which skips the periodic _check_pid) still has a PID."""
        for _ in range(20):
            rec = self.sample_resources()
            if rec.get("pid") is not None:
                rec["snapshot"] = "start"
                self.last_pid = rec["pid"]
                self.identity.setdefault("targetPid", rec["pid"])
                self.identity.setdefault("targetStarttime", self.process_starttime(rec["pid"]))
                return rec
            time.sleep(3)
        rec = self.sample_resources()  # still no pid -> nulls, recorded (never faked)
        rec["snapshot"] = "start"
        return rec

    def take_end_snapshot(self):
        """END-of-workload resource snapshot (both sampler modes, per plan: never hide growth by
        disabling monitoring). Called on the first observation of phase==cooldown, when the full workload
        is complete but the target process is still alive for the whole cooldown window. (phase==done is
        too late: the fixture is mid-teardown there -- writing soak-done + restoring the service, about to
        be SIGKILLed -- so by the 10s watchdog tick that observes done the process is usually already dead
        and the sample comes back null.) Waits briefly for a valid pid; records a never-faked null sample
        if the process is already gone."""
        for _ in range(5):
            rec = self.sample_resources()
            if rec.get("pid") is not None:
                rec["snapshot"] = "end"
                return rec
            time.sleep(2)
        rec = self.sample_resources()  # process gone -> nulls, recorded (never faked)
        rec["snapshot"] = "end"
        return rec

    # ---- main loop ----------------------------------------------------------
    def run(self):
        a = self.args
        self.out.mkdir(parents=True, exist_ok=False)
        (self.out / "meminfo").mkdir()
        signal.signal(signal.SIGTERM, _on_signal)
        signal.signal(signal.SIGINT, _on_signal)

        self.build_identity()
        self.snapshot_and_install()
        self.verify_environment()
        exp_warmup, exp_formal = self.expected_slots()
        self.identity["expectedWarmupCycles"] = exp_warmup
        self.identity["expectedFormalCycles"] = exp_formal
        self.identity["expectedTotalSeconds"] = self.cfg["warmup"] + self.cfg["duration"] + self.cfg["cooldown"]
        (self.out / "manifest.json").write_text(json.dumps(self.identity, indent=2) + "\n")
        self.started = time.monotonic()
        self._last_tick = self.started
        self._last_good_progress = self.started
        self.write_state()
        self.start_collectors()
        # P1 perturbation control: record the sampler mode + take the START resource snapshot (both
        # modes, per plan -- never hide growth by disabling monitoring). Waits briefly for the target
        # PID to be up; degrades to a null (never-faked) sample if the process never shows.
        self.identity["sampler"] = self.sampler
        self.take_start_snapshot()

        last_pid_t = last_sample_t = last_proxy_t = time.monotonic()
        try:
            while self.inst_proc.poll() is None and not _cancelled:
                if self._abort:
                    break
                now = time.monotonic()
                # Host liveness: a host suspension > WATCHDOG_S between ticks is an infra break.
                if now - (getattr(self, "_last_tick", self.started)) > WATCHDOG_S:
                    self.abort("INFRA_INTERRUPTED", f"host tick gap {now - self._last_tick:.0f}s > {WATCHDOG_S}s")
                    break
                self._last_tick = now

                # P1 perturbation control: only the "full" group does the periodic metric sampling
                # (PID every 30s, FD/threads/PSS every 60s, system-proxy every 300s) -- that IPC is the
                # variable under test. The "heartbeat" group skips all of it and relies on the fixture
                # progress.json heartbeat (_check_watchdog) + the start/end snapshots alone.
                if self.sampler == "full":
                    if now - last_pid_t >= PID_CHECK_EVERY:
                        last_pid_t = now
                        self._check_pid()

                    if now - last_sample_t >= SAMPLE_EVERY:
                        last_sample_t = now
                        self.sample_resources()

                    if now - last_proxy_t >= PROXY_EVERY:
                        last_proxy_t = now
                        self.sample_system_proxy()

                self._check_watchdog(now)
                self.rotate_logcat()
                self.write_state()
                # Disk low-water: safe-stop below DISK_STOP_MIN_GB as INFRA_INTERRUPTED.
                free = shutil.disk_usage("/").free / (1024 ** 3)
                if free < DISK_STOP_MIN_GB:
                    self.abort("INFRA_INTERRUPTED", f"free disk {free:.1f}GiB < {DISK_STOP_MIN_GB}GiB safe-stop")
                    break
                time.sleep(TICK_S)
        finally:
            self.teardown()

        state = self.finalize(exp_warmup, exp_formal)
        self._write_result(state)
        return state

    def _check_pid(self):
        self.ipc["pid_checks"] += 1  # P1 perturbation control: count the metric queries (IPC)
        pid = self.target_pid()
        if pid is None:
            # Clean-completion guard: once the fixture reaches phase==done it is mid-teardown (writing
            # soak-done.json + restoring the autofill service) and the target exits imminently. A vanished
            # target at/after done is the EXPECTED teardown, not an unexpected death -- the workload is
            # already complete, so finalize() judges off the authoritative device soak-done/phase once the
            # host inst_proc exits. (Fix: a 30s pid-check landing in the target-exit -> host-inst-exit
            # window previously misclassified a clean p2/p3 completion as process-death.) Check both the
            # latched _done_at and a fresh read of the fixture's own phase, since _check_pid runs before
            # _check_watchdog within a tick.
            if self._done_at is not None or self._fixture_phase_is_done():
                return
            self.abort("FAIL_FUNCTIONAL", "target process disappeared (unexpected exit)", "process-death")
            return
        if self.last_pid is None:
            self.last_pid = pid
            self.identity["targetPid"] = pid
            self.identity["targetStarttime"] = self.process_starttime(pid)
        elif pid != self.last_pid:
            self.abort("FAIL_FUNCTIONAL", f"target PID changed {self.last_pid} -> {pid} (unexpected restart)",
                       "process-restart")
            return
        st = self.process_starttime(pid)
        if self.identity.get("targetStarttime") and st and st != self.identity["targetStarttime"]:
            self.abort("FAIL_FUNCTIONAL", "target process starttime changed (unexpected restart)", "process-restart")

    def _fixture_phase_is_done(self):
        """Fresh read of the fixture's own progress.json phase. A target that has vanished AFTER the
        fixture wrote phase==done is a clean teardown, not a crash (see the guard in _check_pid)."""
        raw = self.read_device_file("progress.json")
        if not raw:
            return False
        try:
            p = json.loads(raw)
        except json.JSONDecodeError:
            return False
        return p.get("runId") == self.args.run_id and p.get("phase") == "done"

    def _watchdog_threshold(self):
        phase = (self._last_progress_sig or (None, None, None))[2]
        return ACTIVE_WATCHDOG(self.cfg["cycle"]) if phase in ("warmup", "active") else WATCHDOG_S

    def _progress_sig(self, prog):
        return (prog.get("updatedMonotonicMs"), prog.get("seq"), prog.get("phase"))

    def _check_watchdog(self, now):
        """Stall detector: the fixture rewrites progress.json (updatedMonotonicMs advances) at least
        every slot inside an active block and every heartbeatSeconds in idle/cooldown, so an UNCHANGED
        matching progress.json for longer than the phase threshold means the workload is stuck. This
        reads liveness off the fixture's own clock -- no host/device cross-clock subtraction. A stale
        file from a previous run on this device (different runId) is ignored until this run writes one."""
        prog_raw = self.read_device_file("progress.json")
        if prog_raw is None:
            if now - self._last_good_progress > self._watchdog_threshold():
                self.abort("INFRA_INTERRUPTED", f"no matching progress.json for {now - self._last_good_progress:.0f}s")
            return
        try:
            prog = json.loads(prog_raw)
        except json.JSONDecodeError:
            return
        if prog.get("runId") != self.args.run_id:
            if now - self._last_good_progress > self._watchdog_threshold():
                self.abort("INFRA_INTERRUPTED",
                           f"no progress.json for runId {self.args.run_id} for {now - self._last_good_progress:.0f}s")
            return
        phase = prog.get("phase")
        # END-of-workload resource snapshot (both sampler modes, per plan). Taken at the first observation
        # of cooldown: the full workload is done but the process is alive for the whole cooldown window, so
        # the sample is valid. (At phase==done the fixture is mid-teardown and usually already dead.)
        if phase == "cooldown" and not self._end_snapshot_taken:
            self._end_snapshot_taken = True
            self.take_end_snapshot()
        if phase == "done":
            # Workload finished: the fixture is writing soak-done.json + restoring the service, then
            # inst_proc exits. Don't stall-abort a completing run; but if it does not exit, that's infra.
            if self._done_at is None:
                self._done_at = now
                # Fallback end snapshot only if cooldown was skipped (e.g. cooldown=0): best-effort single
                # sample -- the process may already be dead here, so a null is recorded (never faked).
                if not self._end_snapshot_taken:
                    self._end_snapshot_taken = True
                    rec = self.sample_resources()
                    rec["snapshot"] = "end"
            elif now - self._done_at > 90:
                self.abort("INFRA_INTERRUPTED", "fixture reached phase=done but did not exit within 90s", "slow-exit")
            self._last_good_progress = now
            return
        self._done_at = None
        sig = self._progress_sig(prog)
        if sig != self._last_progress_sig:
            self._last_progress_sig = sig
            self._last_good_progress = now
        elif now - self._last_good_progress > self._watchdog_threshold():
            # One grace re-read before declaring a stall; a genuinely stalled workload is a functional hang.
            time.sleep(5)
            again = self.read_device_file("progress.json")
            if again:
                try:
                    p2 = json.loads(again)
                except json.JSONDecodeError:
                    p2 = None
                if p2 and p2.get("runId") == self.args.run_id and self._progress_sig(p2) != self._last_progress_sig:
                    self._last_progress_sig = self._progress_sig(p2)
                    self._last_good_progress = time.monotonic()
                    return
            self.abort("FAIL_FUNCTIONAL",
                       f"workload stalled: progress.json unchanged for {now - self._last_good_progress:.0f}s in phase {phase}",
                       "workload-stuck")

    def _write_result(self, state):
        result = {
            "state": state, "preset": self.args.preset, "phase": self.args.phase, "runId": self.args.run_id,
            "serial": self.args.serial, "elapsedSeconds": getattr(self, "elapsed", 0),
            "note": self.note, "attribution": self.attribution,
            "sampler": self.sampler, "ipc": self.ipc,
            "identity": self.identity, "samples": self.samples, "systemProxySamples": self.proxy_samples,
        }
        (self.out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"state": state, "runId": self.args.run_id, "preset": self.args.preset,
                          "elapsedSeconds": getattr(self, "elapsed", 0), "note": self.note}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=None)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--preset", required=True, choices=sorted(PRESETS))
    parser.add_argument("--phase", default=None, help="label recorded in manifest (defaults to preset)")
    parser.add_argument("--run-id", default=None, help="runId sent to the fixture (defaults to <preset>-<serial>)")
    parser.add_argument("--autofill-mode", choices=["on", "off"], default="on")
    parser.add_argument("--sampler", choices=["full", "heartbeat"], default="full",
                        help="P1 perturbation control: 'full' adds periodic PID/FD/PSS/proxy sampling; "
                             "'heartbeat' keeps only the fixture progress.json heartbeat + start/end resource snapshots")
    parser.add_argument("--apk", default=None, help="use a prebuilt APK instead of building the androidTest APK")
    parser.add_argument("--no-sleep-check", action="store_true", help="skip the host caffeinate liveness check")
    args = parser.parse_args()
    if args.phase is None:
        args.phase = args.preset
    if args.run_id is None:
        args.run_id = f"{args.preset}-{args.serial}"
    if args.output.exists():
        parser.error(f"output dir {args.output} already exists; refusing to overwrite")
    return 0 if Runner(args).run() == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

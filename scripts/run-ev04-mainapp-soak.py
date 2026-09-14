#!/usr/bin/env python3
"""EV-04 host runner -- the MAIN-APP COMBINED 2h soak (master plan:58-68).

This is the HOST side of EV-04. It launches the device-side combined fixture
(`MainAppCombinedSoakDeviceTest`, class/method names still placeholder -- see the TODOs below)
ONCE for ONE round (one flavor on one API), owns the wait, and judges the result from
FORENSICS-FIRST evidence -- the `am instrument` stdout stream (OK (1 test) / FAILURES!!!) +
logcat 'TestRunner: run finished: 1 tests, 0 failed' + the fixture's own per-cycle log (pid)
+ the device's soak-done.json / cycles.jsonl -- and NEVER from INSTRUMENTATION_CODE or a raw
exit 0 (teardown SIGKILLs the finished inst process -> -1, benign).

JUDGMENT MODEL (the part that DIFFERS from EV-02/EV-03 -- see scoping ev04-mainapp-scoping.md
§4/§5 and master plan:58-68):
    classify() =  per-task-class assertions  +  goal success/Stop parity (odd/even blocks, 6/6).
  * It is NOT a resource gate (that is EV-03 / hxa103 `--workload app`: FD+8/thread+16/PSS+96MiB).
  * It is NOT an autofill-restore check (that is EV-02).
  * "4 rounds green" = 2 API (29/36) x 2 flavor (consumer/developer). The runner judges a SINGLE
    round green; the 4-round aggregation is done by the OUTER serial invocation of this same
    runner (see scoping §5 "4 轮 = 2 API x 2 flavor 由外层串行调用同一 runner 完成").

The per-block workload shape (master plan:58-66, encoded as presets below, frozen -- never dynamic):
  * round2h: 12 blocks x 600s (= 2h). Each 10-min block runs the FIVE task classes once
    (consumer) then a resource sample + idle to fill the block; tasks are <=90s, no concurrent
    catch-up. developer adds PRoot + CLI each block (consumer replaces those with a
    "channel absent / cannot register" assertion).
  * goal parity: odd blocks do a SUCCESS goal, even blocks do a Stop goal; over 12 blocks that is
    exactly 6 success + 6 Stop = 6/6 (master plan:64 "奇偶块交替成功/Stop，总数各6").
  * pilot: 1 block x 5 classes (~10-15min) -- a HARNESS VALIDATION (validates the drive level +
    that each class assertion is judgeable), NEVER a 2h acceptance.

§5 host contract (forked from run-hxa103-browser-soak.py) -- no app install, no data clear:
  1. Fixed artifact identity -- pin git commit + working diff + the INSTALLED consumer/developer
     app APK + the matching androidTest APK sha256 so the round is attributable to artifacts.
  2. Single-process tracking -- the fixture's own `pid=` log is authoritative, host `pidof` is
     corroboration; assert the app process never restarted (no same-process restart).
  3. Sampling-failure attribution -- an independent app-process PSS lens; a failed sample degrades
     to a LABELLED, attributed record (never silently dropped, NEVER faked to 0), and it is
     corroboration only -- it is NOT a resource gate in EV-04.
  4. Forensics-first judgment -- decide from the real evidence above, never from exit code.

Result states (closed set, shared with the EV-02/EV-03 runners so result.json stays comparable):
    PASS / FAIL_FUNCTIONAL / FAIL_RESOURCE / INFRA_INTERRUPTED / INCONCLUSIVE / CANCELLED
  NOTE on FAIL_RESOURCE: EV-04 is NOT a resource gate. This state exists only for the closed-set
  schema; classify() returns it ONLY if a device-side resource bound is breached in the
  instrumentation stream (the `cumulative (FD|thread|PSS) drift:` marker) -- a defensive branch
  mirroring hxa103 so the dispatcher stays complete. Under EV-04's business fixture that marker is
  not expected to appear; the round's PASS/FAIL is decided by the per-class + goal-parity gates.

No product code is added or modified; this is test-harness / evidence only (scoping: test-only).
"""
import argparse
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

# ---- per-flavor packages (master plan:30 "API29/36 x 两flavor"; scoping §3) --------
# consumer base carries all five task classes (files/browser/mcp/a2a/goal, build.gradle.kts:76-98);
# developer additionally carries PRoot/CLI (build.gradle.kts:122-132). The runner samples the APP
# process (app_pkg) and drives the test via the test_pkg instrumentation runner.
FLAVORS = {
    "consumer": {"app_pkg": "com.helix.agent", "test_pkg": "com.helix.agent.test"},
    "developer": {"app_pkg": "com.helix.agent.developer", "test_pkg": "com.helix.agent.developer.test"},
}

# The instrumented test runner (scoping §3: testInstrumentationRunner = com.helix.app.HelixAndroidJUnitRunner,
# which also pins the UI language to 简体中文 -- the assertions rely on that deterministic locale).
RUNNER_CLASS = "com.helix.app.HelixAndroidJUnitRunner"

# The combined-soak fixture (U4): one instrumented method that loops in-process over the 12-block
# grid. The class/method below are the REAL, implemented targets (confirmed against
# app/src/androidTest/kotlin/com/helix/app/MainAppCombinedSoakDeviceTest.kt). It drives the real
# HelixApplication.appContainer / ChatService / ToolDispatcher programmatically (MainActivity is
# launched once for the real process + container) and writes progress.json / cycles.jsonl /
# heartbeat.jsonl / soak-done.json under the test package's external files dir, logging
# `HelixSoak:pid=<pid>` per cycle (grepped below by FIXTURE_PID_RE).
TARGET_CLASS = "com.helix.app.MainAppCombinedSoakDeviceTest"
TARGET_METHOD = "mainAppCombinedSoak"

# Device-side evidence files the fixture writes under the APP package's external files dir.
# The app's androidTest instrumentation runs in the APP process (com.helix.agent), NOT the test
# uid (com.helix.agent.test) — see app/src/androidTest AndroidManifest — so
# getExternalFilesDir(null) resolves to the APP package dir, hence {app_pkg} (not {test_pkg}).
DEVICE_FILES = "/sdcard/Android/data/{app_pkg}/files"

RESULT_STATES = ["PASS", "FAIL_FUNCTIONAL", "FAIL_RESOURCE", "INFRA_INTERRUPTED", "INCONCLUSIVE", "CANCELLED"]

# The FIVE base task classes (master plan:58-64) + the two developer-only classes (master plan:66).
# consumer replaces proot/cli with a "channel absent / cannot register" assertion (build.gradle:
# connectorInstallationService is null in consumer), so a consumer round is expected to have the five
# base classes green and NO proot/cli classes at all.
BASE_CLASSES = ["chat", "files", "browser", "mcp", "goal"]
DEVELOPER_EXTRA = ["proot", "cli"]

# ---- frozen workload presets (master plan:58 "每10分钟块 ... 共12块、60个业务任务，任务最长90秒") --
# The ONLY place the difference between the ~15min pilot and the 2h round lives; never dynamic.
PRESETS = {
    # 1 block x 5 classes (~10-15min). HARNESS VALIDATION: proves the drive level works and each
    # class assertion is judgeable. goal parity in 1 block = 1 success (block 1 is odd) + 0 Stop.
    "pilot": dict(blocks=1, block_seconds=600, task_max_seconds=90, heartbeat_seconds=30),
    # 12 blocks x 600s = 7200s = 2h. The EV-04 round. 60 tasks (consumer) / 84 (developer).
    # goal parity = 6 success (odd blocks 1,3,5,7,9,11) + 6 Stop (even blocks 2,4,6,8,10,12) = 6/6.
    "round2h": dict(blocks=12, block_seconds=600, task_max_seconds=90, heartbeat_seconds=30),
}

# Host sampling cadence + liveness watchdog + disk high/low water marks (scoping §4: reuse §5 scaffolding).
TICK_S = 10
PID_CHECK_EVERY = 30          # host app-process PID recheck (single-process corroboration)
SAMPLE_EVERY = 60             # host app-process PSS sample (independent lens, corroboration only)
WATCHDOG_S = 180              # no progress.json advance for this long -> stall
# A block is 600s with per-task (<=90s) + heartbeat (30s) progress writes, so a 180s threshold is
# comfortably inside the slowest expected gap; scale for larger grids, never below 180.
ACTIVE_WATCHDOG = lambda task_max: max(WATCHDOG_S, task_max + 90)
DISK_START_MIN_GB = 20
DISK_STOP_MIN_GB = 5
LOGCAT_ROTATE_MB = 200

# Device-side resource-gate breach marker (defensive FAIL_RESOURCE branch; mirrors hxa103). EV-04 is
# not a resource gate, so this is expected to be absent in a well-formed EV-04 stream.
GATE_DRIFT_RE = re.compile(r"cumulative (FD|thread|PSS) drift:\s*(\d+)\s*->\s*(\d+)")
# The fixture logs its own pid per task/cycle; this is the AUTHORITATIVE single-process evidence
# (host `pidof` is corroboration).
FIXTURE_PID_RE = re.compile(r"HelixSoak:.*pid=(\d+)")
TEST_RUNNER_OK_RE = re.compile(r"TestRunner:\s+run finished:\s*1 tests,\s*0 failed")

_cancelled = False


def _on_signal(signum, _frame):
    global _cancelled
    _cancelled = True


# =====================================================================
#  Pure, device-independent decision logic (unit-tested by
#  scripts/test-run-ev04-mainapp-soak-logic.py). No I/O in this block.
# =====================================================================

def expected_classes(flavor):
    """The task classes a round of `flavor` is expected to have green (master plan:58-66)."""
    return BASE_CLASSES + (DEVELOPER_EXTRA if flavor == "developer" else [])


def check_classes(flavor, observed):
    """Pure. `observed` maps class name -> status. Returns the list of classes that are NOT green
    (missing or status != 'ok'). For consumer this checks the five base classes; for developer it
    also requires proot + cli. (consumer's 'channel absent' assertion is the fixture-internal way of
    making proot/cli a green consumer assertion; the runner only expects the five base classes.)"""
    failures = []
    for c in expected_classes(flavor):
        if observed.get(c) != "ok":
            failures.append(c)
    return failures


def expected_slots(preset, flavor):
    """Pure. The expected per-round workload shape for `preset`/`flavor` (mirrors EV-02's
    expected_slots). Odd blocks run a SUCCESS goal, even blocks a Stop goal, so over `blocks` blocks:
        goalSuccess = ceil(blocks/2), goalStop = floor(blocks/2).
    For round2h (12 blocks) that is exactly 6/6 (master plan:64); for pilot (1 block) 1/0.
    Returns a dict the runner records in the manifest AND classify() compares against."""
    cfg = PRESETS[preset]
    blocks = cfg["blocks"]
    per_block = len(expected_classes(flavor))
    return {
        "preset": preset,
        "flavor": flavor,
        "blocks": blocks,
        "goalSuccess": (blocks + 1) // 2,
        "goalStop": blocks // 2,
        "perBlock": per_block,
        "totalTasks": blocks * per_block,        # 60 (consumer) / 84 (developer) for round2h
        "blockSeconds": cfg["block_seconds"],
        "taskMaxSeconds": cfg["task_max_seconds"],
        "durationSeconds": blocks * cfg["block_seconds"],   # 7200 (2h) for round2h
    }


def build_fixed_identity(raw):
    """Pure. Assemble the §5.1 fixed-artifact identity dict from ALREADY-GATHERED values (no I/O here,
    so it is unit-testable). Pins git commit + working diff + the INSTALLED app/test APK sha256, so a
    round is attributable to specific artifacts. No app install / no data clear happens in this runner
    (the operator pre-installs, mirroring hxa103 §5)."""
    return {
        "preset": raw["preset"],
        "flavor": raw["flavor"],
        "runId": raw["runId"],
        "serial": raw["serial"],
        "gitCommit": raw["gitCommit"],
        "workingDiffSha256": hashlib.sha256(raw["workingDiff"]).hexdigest(),
        "untrackedTestFiles": raw.get("untrackedTestFiles", ""),
        "appPackage": raw["appPkg"],
        "testPackage": raw["testPkg"],
        "component": f"{raw['testPkg']}/{RUNNER_CLASS}",
        "testClass": TARGET_CLASS,
        "testMethod": TARGET_METHOD,
        "installedArtifacts": {
            "app": {"path": raw["installedApp"]["path"], "sha256": raw["installedApp"]["sha256"]},
            "test": {"path": raw["installedTest"]["path"], "sha256": raw["installedTest"]["sha256"]},
        },
        # Optional operator-provided APK paths (cross-checked against the installed sha below).
        "providedApkSha256": {"app": raw.get("providedAppSha"), "test": raw.get("providedTestSha")},
        "device": {
            "api": raw.get("api"), "avd": raw.get("avd"),
            "fingerprint": raw.get("fingerprint"), "abi": raw.get("abi"),
            "bootId": raw.get("bootId"), "ramKb": raw.get("ramKb"),
        },
        "config": raw["config"],
    }


def classify(ev):
    """Pure forensics-first judgment for ONE EV-04 round. `ev` is a dict of EVIDENCE (see run());
    returns (state, note, attribution). Device-independent -> unit-testable without an emulator.

    Priority: CANCELLED > INFRA > FAIL_RESOURCE(defensive device-side drift) > FAIL_FUNCTIONAL
    (stream / logcat / soak-done / per-class / goal-parity / single-pid restart) > single-pid
    evidence > full-duration > PASS (sampling attributed in the note).

    The EV-04-specific gates are the per-task-class assertions and the goal success/Stop parity;
    they are FUNCTIONAL (business) failures, never resource failures. The app-process PSS sampling
    is corroboration only -- a run can be fully green while sampling degrades, and it is NEVER a
    reason for FAIL_RESOURCE (EV-04 is not a resource gate).
    """
    preset = ev.get("preset", "pilot")
    flavor = ev.get("flavor", "consumer")

    if ev.get("cancelled"):
        return ("CANCELLED",
                "runner stopped cleanly before the requested duration",
                "operator stop")
    if ev.get("infraError"):
        return ("INFRA_INTERRUPTED", ev["infraError"], ev["infraError"])

    inst = ev.get("instStream", "")

    # Defensible closed-set completeness: if a device-side resource bound were breached, its
    # assertion marker surfaces in the stream. EV-04 does not enforce such a gate, so this branch is
    # expected to be dead in a well-formed EV-04 round (documented above). Mirrors hxa103 ordering.
    drift = GATE_DRIFT_RE.search(inst)
    if drift:
        return ("FAIL_RESOURCE",
                f"device-side resource bound breached in stream: {drift.group(1)} {drift.group(2)} -> "
                f"{drift.group(3)} (EV-04 is NOT a resource gate; unexpected marker)",
                "defensive resource marker (master plan: EV-04 has no FD/thread/PSS gate)")

    ok_stream = ("OK (1 test)" in inst) and ("FAILURES!!!" not in inst)
    # FORENSICS-FIRST: we read the stream TEXT, never INSTRUMENTATION_CODE / exit 0. Teardown
    # SIGKILLs the finished-inst process -> -1, which is benign and must not flip a green run.
    if not ok_stream:
        if "FAILURES!!!" in inst:
            return ("FAIL_FUNCTIONAL",
                    "am-instrument stream reports FAILURES!!!",
                    "nested fixture failure (see instrumentation.log)")
        return ("FAIL_FUNCTIONAL",
                "am-instrument stream is not 'OK (1 test)'",
                "test did not report success")

    # logcat TestRunner must confirm 1 test / 0 failed (forensics-first; mirrors EV-02 functional_ok).
    if not ev.get("logcatFinished"):
        return ("FAIL_FUNCTIONAL",
                "logcat missing 'TestRunner: run finished: 1 tests, 0 failed'",
                "test-runner success marker absent from logcat")

    # The fixture's terminal record must exist.
    done = ev.get("done")
    if done is None:
        return ("FAIL_FUNCTIONAL",
                "soak-done.json missing",
                "fixture terminal record absent")

    # ---- EV-04 business gate (a) : per-task-class assertions ----------------
    # The per-class status lives in soak-done.json (the fixture's terminal record); lift it out if
    # the caller didn't provide it explicitly.
    task_classes = ev.get("taskClasses")
    if task_classes is None:
        task_classes = (done or {}).get("taskClasses", {})
    class_failures = ev.get("classFailures")
    if class_failures is None:
        class_failures = check_classes(flavor, task_classes)
    if class_failures:
        return ("FAIL_FUNCTIONAL",
                f"per-task-class assertion failed/missing for: {', '.join(class_failures)}",
                "task-class assertion (master plan:60-66)")

    if done.get("goalMechanism") != "model-report-user-pause-v1":
        return ("FAIL_FUNCTIONAL", "missing/current Goal mechanism identity mismatch",
                "legacy Goal fixture is not new-main acceptance")

    # ---- EV-04 business gate (b) : goal success/Stop parity (6/6 for 12 blocks)
    exp = expected_slots(preset, flavor)
    obs_success = done.get("goalSuccess")
    obs_stop = done.get("goalStop")
    if obs_success != exp["goalSuccess"] or obs_stop != exp["goalStop"]:
        return ("FAIL_FUNCTIONAL",
                f"goal success/Stop parity not met: observed success={obs_success} stop={obs_stop} "
                f"!= expected success={exp['goalSuccess']} stop={exp['goalStop']} "
                f"(odd blocks success / even blocks Stop; 6/6 for round2h)",
                "goal parity (master plan:64)")

    # ---- single-process invariant (fixture pid= authoritative + host pidof corroboration)
    pids = ev.get("pids") or ev.get("fixturePids") or ev.get("hostPids") or set()
    if len(pids) > 1:
        return ("FAIL_FUNCTIONAL",
                f"target app process restarted during the round (pids: {sorted(pids)})",
                "same-process invariant violated")
    if not pids:
        return ("INCONCLUSIVE",
                "no process-id evidence (fixture pid= and host pidof both empty); cannot confirm "
                "same-process for this round",
                "single-process evidence missing")

    # ---- full-duration check (stream green but shorter than requested -> not a full round)
    elapsed = ev.get("elapsedSec")
    seconds = ev.get("seconds")
    if elapsed is not None and seconds is not None and elapsed < seconds:
        return ("INCONCLUSIVE",
                f"stream OK but host elapsed {elapsed:.0f}s < requested {seconds}s "
                f"(the round did not run the full requested duration)",
                "duration not fully elapsed")

    # ---- green: build the PASS note (sampling attributed, never faked) --------
    samp = ev.get("sampling", {})
    total = samp.get("total", 0)
    ok = samp.get("ok", 0)
    if preset == "round2h":
        note = (f"EV-04 {flavor} 2h combined round fully green: single pid, "
                f"{exp['blocks']} blocks, {exp['totalTasks']} tasks, goal "
                f"{obs_success}/{obs_stop} parity, all task classes green")
    else:
        note = (f"EV-04 {flavor} {preset} combined round fully green (single pid, "
                f"{exp['totalTasks']} tasks, goal {obs_success}/{obs_stop}, all task classes green); "
                f"HARNESS VALIDATION -- NOT a 2h acceptance; requires --preset round2h")
    if total and total != ok:
        note += (f"; host app-process PSS sampling {ok}/{total} succeeded "
                 f"({total - ok} attributed failures in samples.jsonl) -- corroboration only, "
                 f"fixture per-class assertions are authoritative")
    return ("PASS", note, "EV-04 combined soak" if preset == "round2h" else "harness validation")


# =====================================================================
#  Runner (device I/O). Forked from run-browser-autofill-soak.py structure, minus the EV-02-only
#  autofill + system-UID-proxy machinery, plus the hxa103 §5 fixed-identity / forensics contract.
# =====================================================================
class Runner:
    def __init__(self, args):
        self.args = args
        self.flavor = FLAVORS[args.flavor]
        self.app_pkg = self.flavor["app_pkg"]
        self.test_pkg = self.flavor["test_pkg"]
        self.device_files = DEVICE_FILES.format(app_pkg=self.app_pkg)
        self.adb = args.adb or shutil.which("adb")
        self.base = [self.adb, "-s", args.serial]
        self.out = args.output
        self.cfg = dict(PRESETS[args.preset])
        self.state = "RUNNING"
        self.note = ""
        self.attribution = ""
        self.samples = []
        self.identity = {}
        self.last_pid = None
        self.logcat_proc = None
        self.inst_proc = None
        self.logcat_seq = 0
        self.logcat_bytes = 0
        self.logcat_fail = 0
        self._last_progress_sig = None
        self._done_at = None
        self._abort = None
        self.host_pids = set()
        self.ipc = {"pid_checks": 0, "resource_samples": 0}

    # ---- adb helpers --------------------------------------------------------
    def shell(self, *words, timeout=30, retries=1):
        cmd = self.base + ["shell", *words]
        last = None
        for attempt in range(retries + 1):
            try:
                return subprocess.check_output(cmd, text=True, timeout=timeout)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired, OSError) as e:
                last = e
            if attempt < retries:
                time.sleep(10)
        raise RuntimeError(f"adb shell {' '.join(words)} failed: {last!r}")

    def shell_ok(self, *words, timeout=20):
        """Best-effort read: returns stdout (stripped) or None on any failure. Never raises, so a
        sampling failure is attributed (recorded), not fatal to the run."""
        try:
            r = subprocess.run(self.base + ["shell", *words], text=True, timeout=timeout,
                               capture_output=True)
            return (r.stdout or "").strip() or None
        except Exception:
            return None

    def read_device_file(self, name):
        raw = self.shell_ok("cat", f"{self.device_files}/{name}", timeout=20)
        return raw.strip() if raw else None

    def device_now_ms(self):
        raw = self.shell_ok("cat", "/proc/uptime", timeout=15)
        return int(float(raw.split()[0]) * 1000) if raw else None

    # ---- §5.1 fixed artifact identity (no install / no data clear) -----------
    def _installed_artifact(self, pkg):
        """pm path + sha256sum of the INSTALLED package (authoritative -- that is what runs)."""
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

    def _local_apk_sha(self, p):
        if not p:
            return None
        path = pathlib.Path(p).resolve()
        if not path.exists():
            raise SystemExit(f"--{p} path does not exist: {path}")
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def build_identity(self):
        a = self.args
        api = self.shell("getprop", "ro.build.version.sdk").strip()
        memtotal = self.shell_ok("cat", "/proc/meminfo") or ""
        mt = re.search(r"MemTotal:\s*(\d+) kB", memtotal)
        raw = {
            "preset": a.preset, "flavor": a.flavor, "runId": a.run_id, "serial": a.serial,
            "gitCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
            "workingDiff": subprocess.check_output(["git", "diff", "HEAD"]),
            "untrackedTestFiles": subprocess.check_output(
                ["git", "status", "--porcelain"], text=True).strip(),
            "appPkg": self.app_pkg, "testPkg": self.test_pkg,
            "installedApp": self._installed_artifact(self.app_pkg),
            "installedTest": self._installed_artifact(self.test_pkg),
            "providedAppSha": self._local_apk_sha(getattr(a, "app_apk", None)),
            "providedTestSha": self._local_apk_sha(getattr(a, "test_apk", None)),
            "api": api,
            "avd": self.shell_ok("getprop", "ro.boot.qemu.avd_name"),
            "fingerprint": self.shell("getprop", "ro.build.fingerprint").strip(),
            "abi": self.shell("getprop", "ro.product.cpu.abi").strip(),
            "bootId": self.shell("cat", "/proc/sys/kernel/random/boot_id").strip(),
            "ramKb": int(mt.group(1)) if mt else None,
            "config": dict(self.cfg, flavor=a.flavor),
        }
        self.identity = build_fixed_identity(raw)
        # Cross-check: if the operator supplied APK paths, their sha must match the installed one,
        # else the run is not attributable to the intended build (fail fast, before any device work).
        for label, provided, installed in (
                ("app", raw["providedAppSha"], raw["installedApp"]["sha256"]),
                ("test", raw["providedTestSha"], raw["installedTest"]["sha256"])):
            if provided and installed and provided != installed:
                raise SystemExit(f"{label} APK sha mismatch: provided {provided} != installed {installed}")
        return self.identity

    def verify_environment(self):
        a = self.args
        assert re.fullmatch(r"emulator-\d+", a.serial), "requires an explicit dedicated emulator serial"
        if not self.adb:
            raise SystemExit("adb not found on PATH")
        if not a.no_sleep_check:
            sleep = subprocess.run(["pgrep", "-x", "caffeinate"], capture_output=True, text=True)
            if sleep.returncode != 0:
                raise SystemExit("host sleep-prevention (caffeinate) is not running; "
                                 "start it or pass --no-sleep-check")
        free_gb = shutil.disk_usage("/").free / (1024 ** 3)
        self.identity["freeDiskGbAtStart"] = round(free_gb, 2)
        if free_gb < DISK_START_MIN_GB:
            raise SystemExit(f"free disk {free_gb:.1f}GiB < {DISK_START_MIN_GB}GiB start minimum")

    # ---- host sampling: independent app-process PSS lens (corroboration only) --
    def target_pid(self):
        raw = self.shell_ok("pidof", self.app_pkg, timeout=15)
        if raw is None:
            return None
        pids = raw.split()
        return int(pids[0]) if len(pids) == 1 else (sorted(map(int, pids))[-1] if pids else None)

    def process_starttime(self, pid):
        raw = self.shell_ok("cat", f"/proc/{pid}/stat", timeout=15)
        if raw is None:
            return None
        rest = raw.rsplit(")", 1)[1].split()
        return rest[19] if len(rest) >= 20 else None

    def parse_pss(self, meminfo_raw):
        for line in meminfo_raw.splitlines():
            if re.match(r"\s*TOTAL\s", line):
                nums = re.findall(r"\d+", line.split("TOTAL", 1)[1])
                if nums:
                    return int(nums[0])
        return None

    def sample_resources(self, snapshot=None):
        """One app-process sample: pid + PSS via `dumpsys meminfo --local <pid>`. An independent lens
        on the product process -- NOT a gate in EV-04. A missing/unparseable sample degrades to
        nulls + an attributed note (ok=False): NEVER faked to 0, never silently dropped."""
        self.ipc["resource_samples"] += 1
        t0 = time.monotonic()
        rec = {"hostSec": round(time.monotonic() - self.started, 1),
               "ok": False, "pid": None, "appPssKb": None, "note": None}
        if snapshot:
            rec["snapshot"] = snapshot
        pid = self.target_pid()
        rec["pid"] = pid
        if pid is not None:
            self.host_pids.add(pid)
        raw = self.shell_ok("dumpsys", "meminfo", "--local", str(pid)) if pid is not None else None
        if raw:
            pss = self.parse_pss(raw)
            rec["appPssKb"] = pss
            rec["ok"] = pss is not None
            rec["note"] = None if pss is not None else "dumpsys meminfo returned but no parseable TOTAL (PSS)"
            if pss is not None:
                (self.out / "meminfo" / f"{int(time.monotonic() - self.started)}s.log").write_text(raw)
        else:
            rec["note"] = ("app process not running (between tasks/blocks) or dumpsys meminfo failed"
                           if pid is None else "dumpsys meminfo failed/timed out")
        rec["sampleMs"] = int((time.monotonic() - t0) * 1000)
        self.samples.append(rec)
        self.append_jsonl("samples.jsonl", rec)
        return rec

    # ---- atomic file helpers ------------------------------------------------
    def append_jsonl(self, name, obj):
        with (self.out / name).open("a") as f:
            f.write(json.dumps(obj) + "\n")

    def write_state(self):
        state = {
            "state": self.state, "preset": self.args.preset, "flavor": self.args.flavor,
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
        self._open_logcat()
        cfg = self.cfg
        args = ["-e", "helix.soak.runId", self.args.run_id,
                "-e", "helix.soak.preset", self.args.preset,
                "-e", "helix.soak.flavor", self.args.flavor,
                "-e", "helix.soak.blocks", str(cfg["blocks"]),
                "-e", "helix.soak.blockSeconds", str(cfg["block_seconds"]),
                "-e", "helix.soak.taskMaxSeconds", str(cfg["task_max_seconds"]),
                "-e", "helix.soak.heartbeatSeconds", str(cfg["heartbeat_seconds"])]
        cmd = (self.base + ["shell", "am", "instrument", "-w", "-r",
                            "-e", "class", f"{TARGET_CLASS}#{TARGET_METHOD}"] + args
               + [f"{self.test_pkg}/{RUNNER_CLASS}"])
        self.inst_proc = subprocess.Popen(cmd, stdout=(self.out / "instrumentation.log").open("w"),
                                          stderr=subprocess.STDOUT)

    def _logcat_paths(self):
        paths = [self.out / f"logcat{'' if self.logcat_seq == 0 else '-' + str(self.logcat_seq)}.log"]
        return paths + sorted(self.out.glob("logcat-*.log"))

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
        names = ["cycles.jsonl", "heartbeat.jsonl", "progress.json", "soak-done.json"]
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
            # Terminal abnormal path only (never a mid-round force-stop on a normal round).
            subprocess.run(self.base + ["shell", "am", "force-stop", self.app_pkg],
                           timeout=30, check=False)
        if self.logcat_proc is not None and self.logcat_proc.poll() is None:
            self.logcat_proc.terminate()
            try:
                self.logcat_proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.logcat_proc.kill()

    # ---- forensics: scan the (possibly rotated) logcat, memory-safe ----------
    def scan_logcat(self):
        res = {"fixturePids": set(), "testRunnerFinished": False, "nFixture": 0, "failureMsgs": []}
        for path in self._logcat_paths():
            if not path.exists():
                continue
            with path.open() as f:
                for line in f:
                    if "HelixSoak:" in line:
                        m = FIXTURE_PID_RE.search(line)
                        if m:
                            res["fixturePids"].add(int(m.group(1)))
                            res["nFixture"] += 1
                    elif TEST_RUNNER_OK_RE.search(line):
                        res["testRunnerFinished"] = True
                    elif "FAILURES!!!" in line or "AssertionError" in line:
                        if len(res["failureMsgs"]) < 20:
                            res["failureMsgs"].append(line.strip()[:300])
        return res

    # ---- finalize -> classify (forensics-first) -----------------------------
    def _build_evidence(self, lc):
        # Effective single-process pids: fixture log is authoritative, host pidof corroborates.
        pids = lc["fixturePids"] or self.host_pids
        # soak-done.json (pulled) carries the per-class status + goal parity the gates compare.
        done_raw = self.read_device_file("soak-done.json")
        done = json.loads(done_raw) if done_raw else None
        return {
            "cancelled": _cancelled,
            "infraError": self._infra,
            "instStream": self._read_stream(),
            "logcatFinished": lc["testRunnerFinished"],
            "done": done,
            "preset": self.args.preset,
            "flavor": self.args.flavor,
            "pids": pids,
            "fixturePids": lc["fixturePids"],
            "hostPids": self.host_pids,
            "taskClasses": (done or {}).get("taskClasses", {}) if done else {},
            "elapsedSec": round(getattr(self, "_elapsed", 0.0), 1),
            "seconds": self.cfg["blocks"] * self.cfg["block_seconds"],
            "sampling": {
                "total": len(self.samples),
                "ok": sum(1 for s in self.samples if s["ok"]),
                "failed": sum(1 for s in self.samples if not s["ok"]),
            },
        }

    def _read_stream(self):
        p = self.out / "instrumentation.log"
        try:
            return p.read_text()
        except Exception:
            return ""

    def finalize(self):
        lc = self.scan_logcat()
        ev = self._build_evidence(lc)
        state, note, attribution = classify(ev)
        # Cross-clock duration refinement (plan §5): a PASS whose host/device/expected durations
        # diverge beyond tolerance is not a continuous full round -> INCONCLUSIVE (never a fake PASS).
        done = ev["done"]
        if state == "PASS" and done and isinstance(done.get("durationMs"), int):
            dev_ms = done["durationMs"]
            host_ms = int(self._elapsed * 1000)
            expected_ms = ev["seconds"] * 1000
            tol_ms = max(120_000, int(0.10 * expected_ms))
            consistent = (abs(host_ms - dev_ms) <= tol_ms and abs(dev_ms - expected_ms) <= tol_ms)
            self.identity["deviceDurationMs"] = dev_ms
            self.identity["hostElapsedMs"] = host_ms
            self.identity["durationConsistent"] = consistent
            if not consistent:
                state = "INCONCLUSIVE"
                note += (f" | host/device duration divergence (host {host_ms}ms vs device "
                         f"{dev_ms}ms vs expected {expected_ms}ms)")
        if self._abort:
            state, note, attribution = self._abort
        self.state, self.note, self.attribution = state, note, attribution
        eff_pids = lc["fixturePids"] or self.host_pids
        result = {
            **self.identity,
            "state": state, "note": note, "attribution": attribution,
            "elapsedSeconds": round(self._elapsed, 1),
            "singlePid": len(eff_pids) == 1,
            "fixturePids": sorted(lc["fixturePids"]),
            "hostPids": sorted(self.host_pids),
            "processModelMatches": bool(lc["fixturePids"] and self.host_pids
                                        and lc["fixturePids"] == self.host_pids),
            "testRunnerFinished": lc["testRunnerFinished"],
            "expected": expected_slots(self.args.preset, self.args.flavor),
            "done": ev["done"],
            "sampling": ev["sampling"],
            "ipc": self.ipc,
            "failureMsgs": lc["failureMsgs"],
        }
        (self.out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"state": state, "runId": self.args.run_id, "preset": self.args.preset,
                          "flavor": self.args.flavor, "elapsedSeconds": result["elapsedSeconds"],
                          "note": note}))
        return state

    # ---- watchdog / pid checks ----------------------------------------------
    def _check_pid(self):
        self.ipc["pid_checks"] += 1
        pid = self.target_pid()
        if pid is None:
            self.abort("FAIL_FUNCTIONAL", "target app process disappeared (unexpected exit)", "process-death")
            return
        if self.last_pid is None:
            self.last_pid = pid
            self.identity["targetPid"] = pid
            self.identity["targetStarttime"] = self.process_starttime(pid)
        elif pid != self.last_pid:
            self.abort("FAIL_FUNCTIONAL", f"target app PID changed {self.last_pid} -> {pid} (unexpected restart)",
                       "process-restart")
            return
        st = self.process_starttime(pid)
        if self.identity.get("targetStarttime") and st and st != self.identity.get("targetStarttime"):
            self.abort("FAIL_FUNCTIONAL", "target app starttime changed (unexpected restart)", "process-restart")

    def _progress_sig(self, prog):
        return (prog.get("updatedMonotonicMs"), prog.get("seq"), prog.get("block"), prog.get("phase"))

    def _check_watchdog(self, now):
        """Stall detector: the fixture rewrites progress.json at least every task (<=90s) and every
        heartbeat in idle, so an UNCHANGED matching progress.json for longer than the threshold means
        the workload is stuck. Reads liveness off the fixture's own clock (no cross-clock subtraction);
        a stale file from a prior run (different runId) is ignored until this run writes one."""
        prog_raw = self.read_device_file("progress.json")
        threshold = ACTIVE_WATCHDOG(self.cfg["task_max_seconds"])
        if prog_raw is None:
            if now - self._last_good_progress > threshold:
                self.abort("INFRA_INTERRUPTED",
                           f"no matching progress.json for {now - self._last_good_progress:.0f}s")
            return
        try:
            prog = json.loads(prog_raw)
        except json.JSONDecodeError:
            return
        if prog.get("runId") != self.args.run_id:
            if now - self._last_good_progress > threshold:
                self.abort("INFRA_INTERRUPTED",
                           f"no progress.json for runId {self.args.run_id} for "
                           f"{now - self._last_good_progress:.0f}s")
            return
        phase = prog.get("phase")
        if phase == "done":
            if self._done_at is None:
                self._done_at = now
            elif now - self._done_at > 90:
                self.abort("INFRA_INTERRUPTED",
                           "fixture reached phase=done but did not exit within 90s", "slow-exit")
            self._last_good_progress = now
            return
        self._done_at = None
        sig = self._progress_sig(prog)
        if sig != self._last_progress_sig:
            self._last_progress_sig = sig
            self._last_good_progress = now
        elif now - self._last_good_progress > threshold:
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
                       f"workload stalled: progress.json unchanged for {now - self._last_good_progress:.0f}s "
                       f"in phase {phase}",
                       "workload-stuck")

    # ---- main loop ----------------------------------------------------------
    def run(self):
        a = self.args
        self.out.mkdir(parents=True, exist_ok=False)
        (self.out / "meminfo").mkdir()
        signal.signal(signal.SIGTERM, _on_signal)
        signal.signal(signal.SIGINT, _on_signal)

        self.build_identity()
        self.verify_environment()
        exp = expected_slots(a.preset, a.flavor)
        self.identity["expected"] = exp
        self.identity["expectedTotalSeconds"] = exp["durationSeconds"]
        (self.out / "manifest.json").write_text(json.dumps(self.identity, indent=2) + "\n")
        self.started = time.monotonic()
        self._last_tick = self.started
        self._last_good_progress = self.started
        self._infra = None
        self.write_state()
        self.start_collectors()
        self.sample_resources(snapshot="start")

        last_pid_t = last_sample_t = time.monotonic()
        try:
            while self.inst_proc.poll() is None and not _cancelled:
                if self._abort:
                    break
                now = time.monotonic()
                if now - self._last_tick > WATCHDOG_S:
                    self._abort = ("INFRA_INTERRUPTED",
                                   f"host tick gap {now - self._last_tick:.0f}s > {WATCHDOG_S}s", "")
                    break
                self._last_tick = now

                if now - last_pid_t >= PID_CHECK_EVERY:
                    last_pid_t = now
                    self._check_pid()
                if now - last_sample_t >= SAMPLE_EVERY:
                    last_sample_t = now
                    self.sample_resources()

                self._check_watchdog(now)
                self.rotate_logcat()
                self.write_state()
                free = shutil.disk_usage("/").free / (1024 ** 3)
                if free < DISK_STOP_MIN_GB:
                    self._abort = ("INFRA_INTERRUPTED",
                                   f"free disk {free:.1f}GiB < {DISK_STOP_MIN_GB}GiB safe-stop", "")
                    break
                time.sleep(TICK_S)
        finally:
            self._elapsed = time.monotonic() - self.started
            if self.inst_proc is not None and self.inst_proc.poll() is None and not _cancelled:
                # The instrumentation itself ran long without finishing -> infra break.
                self._infra = (f"TimeoutError: instrumentation exceeded "
                               f"{exp['durationSeconds']}+{WATCHDOG_S}s without finishing")
            self.teardown()

        state = self.finalize()
        return state


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", required=True, help="dedicated emulator serial (emulator-NNNN)")
    parser.add_argument("--adb", default=None)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--preset", required=True, choices=sorted(PRESETS))
    parser.add_argument("--flavor", required=True, choices=sorted(FLAVORS))
    parser.add_argument("--run-id", default=None,
                        help="runId sent to the fixture (defaults to ev04-<flavor>-<preset>-<serial>)")
    parser.add_argument("--app-apk", default=None,
                        help="path to the installed consumer/developer app APK (sha cross-checked)")
    parser.add_argument("--test-apk", default=None,
                        help="path to the matching androidTest APK (sha cross-checked)")
    parser.add_argument("--no-sleep-check", action="store_true",
                        help="skip the host caffeinate liveness check")
    args = parser.parse_args()

    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("requires an explicit dedicated emulator serial (emulator-NNNN)")
    if args.run_id is None:
        args.run_id = f"ev04-{args.flavor}-{args.preset}-{args.serial}"
    if args.output.exists():
        parser.error(f"output dir {args.output} already exists; refusing to overwrite")
    return 0 if Runner(args).run() == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())

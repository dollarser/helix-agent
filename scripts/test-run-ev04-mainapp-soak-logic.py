#!/usr/bin/env python3
"""Host-logic self-test for the EV-04 main-app combined runner (scripts/run-ev04-mainapp-soak.py).

Device-independent and deterministic: it exercises the runner's PURE decision logic
(`classify`, `expected_slots`, `check_classes`, `build_fixed_identity`) plus the §5 invariants
(single-process, sampling-failure attribution that never fakes 0, forensics-first judgment) against
SYNTHETIC done/cycles/logcat/instrumentation evidence -- so the judgment is verified without an
emulator (and without touching the live EV-02 soaks). Run with:
    python3 scripts/test-run-ev04-mainapp-soak-logic.py

The EV-04 judgment model under test (scoping §4/§5, master plan:58-68):
    per-task-class assertions + goal success/Stop parity (odd/even blocks, 6/6 for round2h)
-- NOT a resource gate (EV-03) and NOT an autofill-restore check (EV-02). Raw signals stay
forensics-first: am-instrument stream + logcat TestRunner + soak-done.json + cycles.jsonl, NEVER
INSTRUMENTATION_CODE / exit 0.
"""
import importlib.util
import hashlib
import json
import pathlib
import shutil
import sys
import tempfile
import time
import types

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ev04", HERE / "run-ev04-mainapp-soak.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
Runner = m.Runner
classify = m.classify

BASE = pathlib.Path(tempfile.mkdtemp(prefix="ev04-logic-"))
RESULTS = []


def check(name, cond, detail=""):
    ok = bool(cond)
    RESULTS.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), "-", name, (("| " + detail) if detail else ""))


def make_runner(preset="round2h", flavor="consumer", run_id="logic"):
    out = BASE / run_id
    out.mkdir(parents=True, exist_ok=True)
    (out / "meminfo").mkdir(exist_ok=True)
    args = types.SimpleNamespace(
        adb="adb", serial="emulator-5554", output=out, preset=preset,
        flavor=flavor, run_id=run_id, app_apk=None, test_apk=None, no_sleep_check=True)
    r = Runner(args)
    r.started = time.monotonic()
    r._last_tick = r.started
    r._last_good_progress = r.started
    r._last_progress_sig = None
    r._done_at = None
    r._abort = None
    r._infra = None
    r._elapsed = 7205.0
    return r, out


# =====================================================================
#  classify(): the forensics-first judgment dispatch (closed result set)
# =====================================================================

def base(**kw):
    """A fully-green EV-04 round2h consumer round: 12 blocks, 5 base classes ok, goal 6/6."""
    ev = {
        "instStream": "OK (1 test)\n",
        "logcatFinished": True,
        "done": {
            "blocks": 12,
            "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 6,
            "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok", "goal": "ok"},
        },
        "preset": "round2h", "flavor": "consumer",
        "pids": {123}, "fixturePids": {123}, "hostPids": {123},
        "elapsedSec": 7205, "seconds": 7200,
        "sampling": {"total": 120, "ok": 118, "failed": 2},
    }
    ev.update(kw)
    return ev


DEVELOPER_DONE = {
    "blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 6,
    "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok", "goal": "ok",
                    "proot": "ok", "cli": "ok"},
}

CASES = [
    # (label, evidence, expected_state)
    # --- closed-set dispatch: the six states ---
    ("round2h consumer fully green -> PASS", base(), "PASS"),
    ("round2h developer fully green (proot+cli ok) -> PASS",
     base(flavor="developer", done=DEVELOPER_DONE), "PASS"),
    ("pilot (1 block) green: goal 1/0, 5 classes -> PASS (harness validation)",
     base(preset="pilot", seconds=600, elapsedSec=605,
          done={"blocks": 1, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 1, "goalStop": 0,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok",
                                "goal": "ok"}}), "PASS"),
    ("per-task-class failure (browser) -> FAIL_FUNCTIONAL",
     base(done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 6,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "fail", "mcp": "ok",
                                "goal": "ok"}}), "FAIL_FUNCTIONAL"),
    ("per-task-class missing (mcp absent) -> FAIL_FUNCTIONAL",
     base(done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 6,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "goal": "ok"}}),
     "FAIL_FUNCTIONAL"),
    ("developer round missing proot -> FAIL_FUNCTIONAL",
     base(flavor="developer",
          done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 6,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok",
                                "goal": "ok", "cli": "ok"}}), "FAIL_FUNCTIONAL"),
    # --- the EV-04-defining gate: goal 6/6 parity ---
    ("goal parity not met (success 5 / stop 6) -> FAIL_FUNCTIONAL",
     base(done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 5, "goalStop": 6,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok",
                                "goal": "ok"}}), "FAIL_FUNCTIONAL"),
    ("goal parity not met (success 6 / stop 5) -> FAIL_FUNCTIONAL",
     base(done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 6, "goalStop": 5,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok",
                                "goal": "ok"}}), "FAIL_FUNCTIONAL"),
    ("goal parity not met (success 7 / stop 5) -> FAIL_FUNCTIONAL",
     base(done={"blocks": 12, "goalMechanism": "model-report-user-pause-v1", "goalSuccess": 7, "goalStop": 5,
                "taskClasses": {"chat": "ok", "files": "ok", "browser": "ok", "mcp": "ok",
                                "goal": "ok"}}), "FAIL_FUNCTIONAL"),
    # --- forensics-first: stream + logcat + soak-done are the signal ---
    ("am-instrument FAILURES (functional, no drift) -> FAIL_FUNCTIONAL",
     base(instStream="FAILURES!!!\nThere was 1 failure:\n"), "FAIL_FUNCTIONAL"),
    ("stream not 'OK (1 test)' (only INSTRUMENTATION_CODE=0) -> FAIL_FUNCTIONAL",
     base(instStream="INSTRUMENTATION_CODE=0\n"), "FAIL_FUNCTIONAL"),
    ("logcat missing TestRunner 0-failed -> FAIL_FUNCTIONAL",
     base(logcatFinished=False), "FAIL_FUNCTIONAL"),
    ("soak-done.json missing -> FAIL_FUNCTIONAL", base(done=None), "FAIL_FUNCTIONAL"),
    ("process restarted (two pids) -> FAIL_FUNCTIONAL",
     base(pids={123, 124}, fixturePids={123, 124}, hostPids={123, 124}), "FAIL_FUNCTIONAL"),
    # --- INCONCLUSIVE: green stream but incomplete forensics ---
    ("no process-id evidence -> INCONCLUSIVE",
     base(pids=set(), fixturePids=set(), hostPids=set()), "INCONCLUSIVE"),
    ("stream OK but duration not met -> INCONCLUSIVE",
     base(elapsedSec=3000), "INCONCLUSIVE"),
    # --- INFRA / CANCELLED ---
    ("host sampling gap -> INFRA_INTERRUPTED",
     base(infraError="RuntimeError: host suspended / sampling gap 190s > 180s", pids=set()),
     "INFRA_INTERRUPTED"),
    ("instrumentation timeout -> INFRA_INTERRUPTED",
     base(infraError="TimeoutError: instrumentation exceeded 7200+180s without finishing"),
     "INFRA_INTERRUPTED"),
    ("cancelled cleanly -> CANCELLED",
     base(cancelled=True, instStream="", pids=set()), "CANCELLED"),
    # --- defensive closed-set completeness: a device-side drift marker (EV-04 has no gate) ---
    ("device-side drift marker in stream -> FAIL_RESOURCE (defensive branch)",
     base(instStream="FAILURES!!!\ncumulative PSS drift: 140000 -> 250000"), "FAIL_RESOURCE"),
    # --- the key "never misjudge on -1" case ---
    ("INSTRUMENTATION_CODE=-1 but stream OK + logcat 0 failed -> PASS (not misjudged on -1)",
     base(instStream="OK (1 test)\nINSTRUMENTATION_CODE=-1\n"), "PASS"),
    # --- sampling degradation: attributed, never a resource FAIL, never faked ---
    ("all host sampling failed (0/120) -> PASS (corroboration only, NOT FAIL_RESOURCE)",
     base(sampling={"total": 120, "ok": 0, "failed": 120}), "PASS"),
]

for label, ev, want in CASES:
    state, note, attribution = classify(ev)
    ok = state == want
    check(label, ok, f"got {state} (want {want})" + ("" if ok else f" note={note!r}"))

# The "all sampling failed" PASS must carry the attribution, not silently drop the failures.
state, note, _ = classify(base(sampling={"total": 120, "ok": 0, "failed": 120}))
check("sampling-failure PASS note attributes the 0/120 (corroboration only)",
      state == "PASS" and "0/120" in note and "corroboration only" in note, note)

# =====================================================================
#  expected_slots(): the frozen per-round workload shape (6/6 parity source)
# =====================================================================
slots = [
    ("round2h consumer blocks=12", m.expected_slots("round2h", "consumer")["blocks"] == 12),
    ("round2h goalSuccess=6", m.expected_slots("round2h", "consumer")["goalSuccess"] == 6),
    ("round2h goalStop=6 (6/6 parity)", m.expected_slots("round2h", "consumer")["goalStop"] == 6),
    ("round2h consumer totalTasks=60", m.expected_slots("round2h", "consumer")["totalTasks"] == 60),
    ("round2h consumer perBlock=5", m.expected_slots("round2h", "consumer")["perBlock"] == 5),
    ("round2h consumer duration=7200s (2h)", m.expected_slots("round2h", "consumer")["durationSeconds"] == 7200),
    ("round2h developer perBlock=7 (5 base + proot + cli)", m.expected_slots("round2h", "developer")["perBlock"] == 7),
    ("round2h developer totalTasks=84", m.expected_slots("round2h", "developer")["totalTasks"] == 84),
    ("round2h developer still 6/6 goal parity",
     m.expected_slots("round2h", "developer")["goalSuccess"] == 6
     and m.expected_slots("round2h", "developer")["goalStop"] == 6),
    ("pilot consumer blocks=1", m.expected_slots("pilot", "consumer")["blocks"] == 1),
    ("pilot consumer goal 1/0 (single odd block = success)",
     m.expected_slots("pilot", "consumer")["goalSuccess"] == 1
     and m.expected_slots("pilot", "consumer")["goalStop"] == 0),
    ("pilot consumer totalTasks=5", m.expected_slots("pilot", "consumer")["totalTasks"] == 5),
]
for label, ok in slots:
    check(label, ok)

# =====================================================================
#  §5 invariant: sampling-failure attribution degrades to nulls, never fakes 0
# =====================================================================
def t_sampling_missing_pid():
    r, _ = make_runner(run_id="smp")
    r.target_pid = lambda: None
    r.shell_ok = lambda *w, **k: None
    rec = r.sample_resources()
    check("sampling: no target pid -> pid/appPssKb null, ok=False, attributed (never 0)",
          rec["pid"] is None and rec["appPssKb"] is None and rec["ok"] is False and rec["note"],
          json.dumps(rec))


def t_sampling_empty_meminfo():
    r, _ = make_runner(run_id="smp2")
    r.target_pid = lambda: 123
    r.shell_ok = lambda *w, **k: None          # every device read fails
    rec = r.sample_resources()
    check("sampling: pid present but meminfo unreadable -> appPssKb null, ok=False, never 0",
          rec["pid"] == 123 and rec["appPssKb"] is None and rec["ok"] is False and rec["note"],
          json.dumps(rec))


def t_sampling_ok():
    r, _ = make_runner(run_id="smp3")
    r.target_pid = lambda: 123
    r.shell_ok = lambda *w, **k: "  TOTAL 150000 200000" if "meminfo" in w else None
    rec = r.sample_resources()
    check("sampling: valid meminfo -> appPssKb parsed (150000), ok=True",
          rec["appPssKb"] == 150000 and rec["ok"] is True, json.dumps(rec))


for fn in (t_sampling_missing_pid, t_sampling_empty_meminfo, t_sampling_ok):
    try:
        fn()
    except Exception as e:
        check(fn.__name__, False, f"raised {type(e).__name__}: {e}")

# =====================================================================
#  §5 invariant: fixed artifact identity (pure; no install / no data clear)
# =====================================================================
def t_fixed_identity():
    diff = b"diff --git a/scripts/x b/scripts/x\n+1\n"
    raw = {
        "preset": "round2h", "flavor": "developer", "runId": "r1", "serial": "emulator-5554",
        "gitCommit": "a73cb8b", "workingDiff": diff,
        "untrackedTestFiles": "?? scripts/run-ev04-mainapp-soak.py",
        "appPkg": "com.helix.agent.developer", "testPkg": "com.helix.agent.developer.test",
        "installedApp": {"path": "/data/app/app.apk", "sha256": "aaa" * 20 + "aa"},
        "installedTest": {"path": "/data/app/test.apk", "sha256": "bbb" * 20 + "bb"},
        "providedAppSha": "aaa" * 20 + "aa", "providedTestSha": "bbb" * 20 + "bb",
        "api": "36", "config": {"blocks": 12},
    }
    ident = m.build_fixed_identity(raw)
    check("identity pins gitCommit", ident["gitCommit"] == "a73cb8b")
    check("identity pins workingDiffSha256 = sha256(working diff)",
          ident["workingDiffSha256"] == hashlib.sha256(diff).hexdigest(), ident["workingDiffSha256"])
    check("identity pins installed app + test APK sha256",
          ident["installedArtifacts"]["app"]["sha256"] == raw["installedApp"]["sha256"]
          and ident["installedArtifacts"]["test"]["sha256"] == raw["installedTest"]["sha256"])
    check("identity component = <test_pkg>/<runner> and testClass placeholder recorded",
          ident["component"] == "com.helix.agent.developer.test/com.helix.app.HelixAndroidJUnitRunner"
          and ident["testClass"] == m.TARGET_CLASS
          and "MainAppCombinedSoakDeviceTest" in ident["testClass"])
    check("identity records flavor + config (attribution to specific artifacts)",
          ident["flavor"] == "developer" and ident["config"]["blocks"] == 12)


try:
    t_fixed_identity()
except Exception as e:
    check("t_fixed_identity", False, f"raised {type(e).__name__}: {e}")

# =====================================================================
#  Frozen contract constants (a drift here breaks the run contract)
# =====================================================================
contract = [
    ("PRESETS round2h blocks=12", m.PRESETS["round2h"]["blocks"] == 12),
    ("PRESETS round2h block_seconds=600 (10-min block)", m.PRESETS["round2h"]["block_seconds"] == 600),
    ("PRESETS task_max_seconds=90 (master plan:58)", m.PRESETS["round2h"]["task_max_seconds"] == 90),
    ("PRESETS pilot blocks=1", m.PRESETS["pilot"]["blocks"] == 1),
    ("BASE_CLASSES = 5 task classes", m.BASE_CLASSES == ["chat", "files", "browser", "mcp", "goal"]),
    ("DEVELOPER_EXTRA = proot+cli", m.DEVELOPER_EXTRA == ["proot", "cli"]),
    ("RESULT_STATES closed set (6)", m.RESULT_STATES ==
     ["PASS", "FAIL_FUNCTIONAL", "FAIL_RESOURCE", "INFRA_INTERRUPTED", "INCONCLUSIVE", "CANCELLED"]),
    ("FLAVORS consumer/developer packages", m.FLAVORS["consumer"]["app_pkg"] == "com.helix.agent"
     and m.FLAVORS["developer"]["app_pkg"] == "com.helix.agent.developer"),
]
for label, ok in contract:
    check(label, ok)

# ---- summary -----------------------------------------------------------
n_pass = sum(1 for _, ok, _ in RESULTS if ok)
failed = [n for n, ok, _ in RESULTS if not ok]
print(f"\n{n_pass}/{len(RESULTS)} EV-04 host-logic checks passed"
      + ("" if not failed else f" -- FAILURES: {failed}"))
shutil.rmtree(BASE, ignore_errors=True)
sys.exit(0 if n_pass == len(RESULTS) else 1)

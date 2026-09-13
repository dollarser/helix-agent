#!/usr/bin/env python3
"""P1 fault round for the EV-02 browser/Autofill host runner (plan §5 fault round).

Exercises the REAL runner code paths -- functional_ok / finalize / _check_pid / _check_watchdog /
sample_resources / the signal handler / the CLI double-submit guard -- with synthetic fault
conditions, and asserts the three plan invariants:
  1. never keeps PASS  (any fault -> a non-PASS terminal state),
  2. never double-submits (one result.json per run; a second run into an existing output dir is refused),
  3. never clears the failure log (evidence files survive a failed finalize).

This is a HOST-side logic test: it needs no device and no background tasks, so it is deterministic
and immune to the session's flaky background-task completion notifications. The one fault that is
most convincing when exercised live -- a real SIGTERM mid-run -> CANCELLED + clean teardown -- is
validated separately against a real emulator (the `fault-interrupt` run). A selftest/fault run is
never a plan milestone.
"""
import importlib.util
import json
import pathlib
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import types

HERE = pathlib.Path(__file__).resolve().parent
RUNNER_PATH = HERE / "run-browser-autofill-soak.py"

spec = importlib.util.spec_from_file_location("soak_runner", RUNNER_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
Runner = mod.Runner

BASE = pathlib.Path(tempfile.mkdtemp(prefix="soak-fault-"))

RESULTS = []


def check(name, cond, detail=""):
    ok = bool(cond)
    RESULTS.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), "-", name, (("| " + detail) if detail else ""))


def make_runner(preset="selftest", autofill="on", run_id="faulttest"):
    out = BASE / f"{run_id}"
    out.mkdir(parents=True, exist_ok=True)
    (out / "meminfo").mkdir(exist_ok=True)
    args = types.SimpleNamespace(
        adb="adb", serial="emulator-5554", output=out, preset=preset,
        phase=preset, run_id=run_id, autofill_mode=autofill, apk=None, no_sleep_check=True)
    r = Runner(args)
    r.started = time.monotonic()
    r._last_tick = r.started
    r._last_good_progress = r.started
    r._last_progress_sig = None
    r._done_at = None
    r._abort = None
    return r, out


def stub_device(r, mapping):
    """read_device_file(name) -> mapping.get(name); pull/verify are no-op (no adb in a logic test)."""
    m = dict(mapping)
    r.read_device_file = lambda name: m.get(name)
    r.pull_device_files = lambda: None

    def _restore():
        r.identity["autofillServiceAfter"] = r.identity.get("autofillServiceBefore", "null")
        r.identity["autofillServiceRestored"] = True
        return r.identity["autofillServiceAfter"]
    r.verify_restore = _restore


def w(p, text):
    p.write_text(text)


# ---- F1: functional / assertion failure -----------------------------------
def t1_functional_failure():
    r, out = make_runner(run_id="f1")
    r.logcat_path = out / "logcat.log"
    w(out / "instrumentation.log", "FAILURES!!!\nThere were 2 failures:\n")
    w(out / "logcat.log", "no success marker here\n")
    w(out / "device-cycles.jsonl",
      json.dumps({"status": "ok", "pid": 100}) + "\n" +
      json.dumps({"status": "fail", "pid": 100}) + "\n")
    done = {"warmupCycles": 1, "formalCycles": 1, "requests": 2, "pid": 100, "durationMs": 70000}
    stub_device(r, {"soak-done.json": json.dumps(done)})
    ok, problems, n, sp = r.functional_ok(done, 1, 1)
    check("F1 functional_ok rejects FAILURES stream + a non-ok cycle",
          ok is False and any("stream" in p for p in problems) and any("cycles not status:ok" in p for p in problems),
          "; ".join(problems))
    state = r.finalize(1, 1)
    check("F1 finalize -> FAIL_FUNCTIONAL (never PASS)", state == "FAIL_FUNCTIONAL", state)
    check("F1 failure log preserved (never cleared)",
          (out / "instrumentation.log").exists() and (out / "device-cycles.jsonl").exists())


def t1b_pid_across_cycles():
    r, out = make_runner(run_id="f1b")
    r.logcat_path = out / "logcat.log"
    w(out / "instrumentation.log", "OK (1 test)\n")
    w(out / "logcat.log", "TestRunner: run finished: 1 tests, 0 failed, 0 ignored\n")
    w(out / "device-cycles.jsonl",
      json.dumps({"status": "ok", "pid": 100}) + "\n" +
      json.dumps({"status": "ok", "pid": 200}) + "\n")  # PID changed between cycles
    done = {"warmupCycles": 1, "formalCycles": 1, "requests": 2, "pid": 100, "durationMs": 70000}
    stub_device(r, {"soak-done.json": json.dumps(done)})
    ok, problems, n, sp = r.functional_ok(done, 1, 1)
    check("F1b functional_ok rejects a PID change across cycles",
          ok is False and any("PID" in p for p in problems), "; ".join(problems))


# ---- F2: stuck (workload-stuck watchdog) ----------------------------------
def t2_stuck():
    r, out = make_runner(run_id="f2")
    r._last_good_progress = r.started - 500          # liveness stalled 500s ago
    r._last_progress_sig = (12345, 7, "active")       # and this sig was already the last one
    prog = {"runId": "f2", "updatedMonotonicMs": 12345, "seq": 7, "phase": "active"}
    stub_device(r, {"progress.json": json.dumps(prog)})
    real_sleep = time.sleep
    time.sleep = lambda s: None                        # skip the 5s grace re-read wait
    try:
        r._check_watchdog(time.monotonic())
    finally:
        time.sleep = real_sleep
    check("F2 watchdog -> FAIL_FUNCTIONAL (workload-stuck) on a stalled progress.json",
          r._abort is not None and r._abort[0] == "FAIL_FUNCTIONAL" and r._abort[2] == "workload-stuck",
          repr(r._abort))


def t2b_no_progress():
    r, out = make_runner(run_id="f2b")
    r._last_good_progress = r.started - 500
    stub_device(r, {"progress.json": None})           # no matching progress.json at all
    r._check_watchdog(time.monotonic())
    check("F2b watchdog -> INFRA_INTERRUPTED when no progress.json",
          r._abort is not None and r._abort[0] == "INFRA_INTERRUPTED", repr(r._abort))


# ---- F3: PID change / process death (_check_pid) --------------------------
def t3_pid():
    r, _ = make_runner(run_id="f3a")
    r.target_pid = lambda: None
    r.read_device_file = lambda name: None   # device-free: no progress.json -> phase unknown
    r._check_pid()
    check("F3a _check_pid: target gone with NO done evidence -> FAIL_FUNCTIONAL (process-death)",
          r._abort is not None and r._abort[0] == "FAIL_FUNCTIONAL" and r._abort[2] == "process-death",
          repr(r._abort))

    # F3d: target gone AFTER the fixture wrote phase==done (fresh read of its own progress.json) =
    # clean teardown, NOT a death. This is the regression the EV-02 p2 OFF misclassification found:
    # a 30s pid-check landing in the target-exit -> host-inst-exit window must not abort process-death.
    r4, _ = make_runner(run_id="f3d")
    r4.target_pid = lambda: None
    r4.read_device_file = lambda name: json.dumps({"runId": "f3d", "phase": "done"})
    r4._check_pid()
    check("F3d _check_pid: target gone after phase=done (fresh read) -> clean completion (no abort)",
          r4._abort is None, repr(r4._abort))

    # F3e: target gone with _done_at already latched (a prior tick saw phase=done) -> no abort, and the
    # short-circuit means no re-read of the device is even needed.
    r5, _ = make_runner(run_id="f3e")
    r5.target_pid = lambda: None
    r5.read_device_file = lambda name: None
    r5._done_at = time.monotonic()
    r5._check_pid()
    check("F3e _check_pid: target gone with _done_at latched -> clean completion (no abort)",
          r5._abort is None, repr(r5._abort))

    r2, _ = make_runner(run_id="f3b")
    r2.last_pid = 100
    r2.target_pid = lambda: 200
    r2._check_pid()
    check("F3b _check_pid: PID change -> FAIL_FUNCTIONAL (process-restart)",
          r2._abort is not None and r2._abort[0] == "FAIL_FUNCTIONAL" and r2._abort[2] == "process-restart",
          repr(r2._abort))

    r3, _ = make_runner(run_id="f3c")
    r3.last_pid = 100
    r3.target_pid = lambda: 100
    r3.identity["targetStarttime"] = "111"
    r3.process_starttime = lambda pid: "222"
    r3._check_pid()
    check("F3c _check_pid: starttime change -> FAIL_FUNCTIONAL (process-restart)",
          r3._abort is not None and r3._abort[0] == "FAIL_FUNCTIONAL" and r3._abort[2] == "process-restart",
          repr(r3._abort))


# ---- F4: missing sample degrades to null, never fakes ---------------------
def t4_missing_sample():
    r, _ = make_runner(run_id="f4a")
    r.root_available = False
    r.target_pid = lambda: None
    r.device_now_ms = lambda: None
    rec = r.sample_resources()
    check("F4a missing sample (no target pid): fd/threads/pssKb null, ok=False, never faked",
          rec["fd"] is None and rec["threads"] is None and rec["pssKb"] is None and rec["ok"] is False,
          json.dumps(rec))

    r2, _ = make_runner(run_id="f4b")
    r2.root_available = True
    r2.target_pid = lambda: 123
    r2.device_now_ms = lambda: 1000
    r2.shell_ok = lambda *w, **k: None                # every device read fails
    rec2 = r2.sample_resources()
    check("F4b missing sample (empty /proc reads): fd/threads/binderLocal null, never 0",
          rec2["fd"] is None and rec2["threads"] is None and rec2["binderLocal"] is None,
          json.dumps(rec2))


# ---- F5: runner interrupt (signal -> CANCELLED) ---------------------------
def t5_signal():
    r, out = make_runner(run_id="f5")
    r.logcat_path = out / "logcat.log"
    w(out / "instrumentation.log", "OK (1 test)\n")                       # GREEN functional evidence
    w(out / "logcat.log", "TestRunner: run finished: 1 tests, 0 failed, 0 ignored\n")
    w(out / "device-cycles.jsonl", json.dumps({"status": "ok", "pid": 100}) + "\n")
    done = {"warmupCycles": 1, "formalCycles": 1, "requests": 2, "pid": 100, "durationMs": 70000}
    stub_device(r, {"soak-done.json": json.dumps(done)})
    r.proxy_samples = [{"available": True}]          # would otherwise be a full PASS
    mod._cancelled = False
    sanity = r.finalize(1, 1)
    mod._on_signal(signal.SIGTERM, None)             # the real handler sets the module global
    try:
        state = r.finalize(1, 1)
    finally:
        mod._cancelled = False
    check("F5 sanity: a fully-green run (proxy available) is PASS", sanity == "PASS", sanity)
    check("F5 signal interrupt -> CANCELLED (never keeps PASS, even on green evidence)", state == "CANCELLED", state)


# ---- F6: never double-submits (CLI refuses an existing output dir) --------
def t6_no_double_submit():
    # A run that already wrote result.json: _write_result writes exactly one file.
    r, out = make_runner(run_id="f6")
    r.state = "FAIL_FUNCTIONAL"
    r.note = "x"
    r.attribution = "y"
    r.elapsed = 5
    r._write_result("FAIL_FUNCTIONAL")
    check("F6 _write_result writes exactly one result.json",
          (out / "result.json").exists() and json.loads((out / "result.json").read_text())["state"] == "FAIL_FUNCTIONAL")
    # The CLI guard: re-running into an existing output dir must be REFUSED before any device work,
    # so a second run can neither overwrite evidence nor double-submit.
    p = subprocess.run([sys.executable, str(RUNNER_PATH), "--serial", "emulator-5554",
                        "--output", str(out), "--preset", "selftest", "--run-id", "f6",
                        "--no-sleep-check"], capture_output=True, text=True)
    refused = (p.returncode != 0) and ("already exists" in (p.stderr + p.stdout))
    check("F6 re-running into an existing output dir is refused (no double-submit/overwrite)",
          refused, f"rc={p.returncode} err={(p.stderr.strip()[:90])}")


def main():
    for fn in (t1_functional_failure, t1b_pid_across_cycles, t2_stuck, t2b_no_progress,
               t3_pid, t4_missing_sample, t5_signal, t6_no_double_submit):
        try:
            fn()
        except Exception as e:  # a harness error in the test itself is a FAIL, not a crash-out
            check(fn.__name__, False, f"raised {type(e).__name__}: {e}")
    n_pass = sum(1 for _, ok, _ in RESULTS if ok)
    print(f"\n{ n_pass }/{len(RESULTS)} fault-round invariants held")
    shutil.rmtree(BASE, ignore_errors=True)
    return 0 if n_pass == len(RESULTS) else 1


if __name__ == "__main__":
    raise SystemExit(main())

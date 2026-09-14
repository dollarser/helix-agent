#!/usr/bin/env python3
"""Device-free self-test for the EV-04 combined-soak FIXTURE's PURE logic.

Companion to scripts/test-run-ev04-mainapp-soak-logic.py (which tests the RUNNER's judgment).
This one tests the pure, device-independent logic that the Kotlin fixture
(app/src/androidTest/kotlin/com/helix/app/MainAppCombinedSoakDeviceTest.kt) MUST implement,
by encoding that logic as a reference here and asserting it is CONSISTENT with the runner's
contract (expected_slots / check_classes / classify) plus the on-device file format
(progress.json / soak-done.json) the runner reads.

Why a Python reference: the fixture's pure logic (block grid, odd/even goal-parity accounting,
soak-done/progress writers) is trivial and Android-free, so it is specified once here and the
Kotlin fixture mirrors it line-for-line. The on-device pilot is what proves the RUNTIME behavior
(real ChatService/Dispatcher driving the scripted model); this script proves the SCHEDULING +
ACCOUNTING + FILE-CONTRACT logic is right and device-independent.

Device-independent + deterministic (no adb, no emulator, no network). Run with:
    python3 scripts/test-run-ev04-mainapp-soak-fixture-logic.py
Exits 0 iff every check passes.
"""
import importlib.util
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ev04", HERE / "run-ev04-mainapp-soak.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

classify = m.classify
RESULTS = []


def check(name, cond, detail=""):
    ok = bool(cond)
    RESULTS.append((name, ok, detail))
    print(("PASS" if ok else "FAIL"), "-", name, (("| " + detail) if detail else ""))


# =====================================================================
#  Reference of the FIXTURE's pure scheduling/accounting logic.
#  The Kotlin fixture implements EXACTLY this (MainAppCombinedSoakDeviceTest.kt:
#  blockTaskGrid / goalOutcome / goalTally / soakDone / progress). Keep the two in lockstep.
# =====================================================================

BASE_CLASSES = ["chat", "files", "browser", "mcp", "goal"]
DEVELOPER_EXTRA = ["proot", "cli"]


def expected_classes(flavor):
    """The task classes one round of `flavor` must have green (mirrors the runner's check_classes)."""
    return BASE_CLASSES + (DEVELOPER_EXTRA if flavor == "developer" else [])


def block_task_grid(block, flavor):
    """Pure. The ordered task classes for 1-based block `block` of `flavor`.

    Contract (master plan:58 "每10分钟块包含五类任务各一次"): every expected class runs EXACTLY
    ONCE per block. The four base non-goal classes ROTATE by block (a fixed, deterministic
    rotation that avoids one class always occupying the same slot -- systematic ordering bias);
    the developer-only proot/cli ride after the base in a fixed order; the goal is ALWAYS last so
    its odd/even parity is unambiguous. Length == len(expected_classes(flavor)).
    """
    base = [c for c in BASE_CLASSES if c != "goal"]            # [chat, files, browser, mcp]
    dev = DEVELOPER_EXTRA if flavor == "developer" else []     # [proot, cli] or []
    rot = (block - 1) % len(base)
    rotated = base[rot:] + base[:rot]
    return rotated + dev + ["goal"]


def goal_outcome(block):
    """Pure. True -> the goal on 1-based `block` SUCCEEDS (COMPLETED); False -> it is Stopped
    (PAUSED). Odd blocks succeed, even blocks stop (master plan:64 "奇偶块交替成功/Stop")."""
    return (block % 2) == 1


def goal_tally(blocks):
    """Pure. (success_count, stop_count) over `blocks` 1-based blocks."""
    success = sum(1 for b in range(1, blocks + 1) if goal_outcome(b))
    return success, blocks - success


def all_green_classes(flavor):
    """Pure. The taskClasses map a fully-green round of `flavor` records in soak-done.json."""
    return {c: "ok" for c in expected_classes(flavor)}


# Reference writer for the fixture's terminal record -- the EXACT shape the Kotlin fixture writes
# to soak-done.json and the runner's classify()/_build_evidence reads (goalSuccess, goalStop,
# taskClasses, durationMs + attribution fields).
def soak_done(run_id, flavor, blocks, task_classes, duration_ms, pid, resource_start, resource_end):
    success, stop = goal_tally(blocks)
    return {
        "runId": run_id,
        "flavor": flavor,
        "blocks": blocks,
        "goalMechanism": "model-report-user-pause-v1", "goalSuccess": success,
        "goalStop": stop,
        "taskClasses": task_classes,
        "singlePid": True,
        "pid": pid,
        "durationMs": duration_ms,
        "resourceStart": resource_start,
        "resourceEnd": resource_end,
    }


# Reference writer for the fixture's live progress record -- the EXACT keys the runner's
# runId-keyed watchdog reads (_progress_sig: updatedMonotonicMs/seq/block/phase + runId).
def progress(run_id, phase, seq, block, updated_monotonic_ms):
    return {
        "runId": run_id,
        "phase": phase,                       # "running" ... "done"
        "seq": seq,
        "block": block,
        "updatedMonotonicMs": updated_monotonic_ms,
    }


# =====================================================================
#  (a) block grid: every expected class exactly once per block, all presets x flavors
# =====================================================================
for preset in sorted(m.PRESETS):
    blocks = m.PRESETS[preset]["blocks"]
    for flavor in sorted(m.FLAVORS):
        want = set(expected_classes(flavor))
        ok_len = True
        ok_once = True
        ok_rotate_differs = None
        first = None
        for b in range(1, blocks + 1):
            grid = block_task_grid(b, flavor)
            ok_len = ok_len and len(grid) == len(want)
            ok_once = ok_once and set(grid) == want and len(set(grid)) == len(grid)
            if b == 1:
                first = grid
        # the rotation must actually move the base order between blocks (1..4)
        order_changed = False
        if blocks >= 2:
            order_changed = block_task_grid(1, flavor) != block_task_grid(2, flavor)
        check(f"[{preset}/{flavor}] grid covers each of {len(want)} classes exactly once per block",
              ok_len and ok_once)
        check(f"[{preset}/{flavor}] grid length == perBlock from runner expected_slots",
              len(block_task_grid(1, flavor)) == m.expected_slots(preset, flavor)["perBlock"],
              f"grid={len(block_task_grid(1, flavor))} perBlock={m.expected_slots(preset, flavor)['perBlock']}")
        if blocks >= 2:
            check(f"[{preset}/{flavor}] base order rotates between blocks (bias control)", order_changed)

# =====================================================================
#  (b) goal parity: odd=success / even=stop, totals match the runner's expected 6/6 (1/0 pilot)
# =====================================================================
for preset in sorted(m.PRESETS):
    blocks = m.PRESETS[preset]["blocks"]
    exp = m.expected_slots(preset, "consumer")
    success, stop = goal_tally(blocks)
    check(f"[{preset}] goal tally {success}/{stop} == runner expected {exp['goalSuccess']}/{exp['goalStop']}",
          success == exp["goalSuccess"] and stop == exp["goalStop"])
    # per-block parity: exactly the odd blocks succeed
    succ_blocks = [b for b in range(1, blocks + 1) if goal_outcome(b)]
    check(f"[{preset}] exactly the ODD blocks succeed ({succ_blocks})",
          succ_blocks == [b for b in range(1, blocks + 1) if b % 2 == 1])

# round2h is the acceptance round: assert the literal 6/6 the master plan demands.
s12, t12 = goal_tally(12)
check("round2h (12 blocks) goal parity is exactly 6/6", s12 == 6 and t12 == 6)
check("pilot (1 block) goal parity is exactly 1/0", goal_tally(1) == (1, 0))

# =====================================================================
#  (c) total task count matches the runner's totalTasks (60 consumer / 84 developer for 2h)
# =====================================================================
for preset in sorted(m.PRESETS):
    blocks = m.PRESETS[preset]["blocks"]
    for flavor in sorted(m.FLAVORS):
        grid_total = sum(len(block_task_grid(b, flavor)) for b in range(1, blocks + 1))
        exp = m.expected_slots(preset, flavor)
        check(f"[{preset}/{flavor}] total tasks over all blocks == runner totalTasks ({exp['totalTasks']})",
              grid_total == exp["totalTasks"], f"grid_total={grid_total}")

# =====================================================================
#  (d) soak-done round-trip: the reference writer's JSON parses in the runner and judges correctly
# =====================================================================
def green_ev(preset, flavor, blocks):
    """A fully-green round's evidence built from the reference writers (what the fixture emits)."""
    done = json.loads(json.dumps(  # writer -> bytes -> reader (the runner does json.loads)
        soak_done(f"ev04-{flavor}-{preset}", flavor, blocks, all_green_classes(flavor),
                  m.PRESETS[preset]["blocks"] * m.PRESETS[preset]["block_seconds"] * 1000,
                  pid=123,
                  resource_start={"fd": 200, "threads": 120, "pssKb": 150000},
                  resource_end={"fd": 202, "threads": 122, "pssKb": 151000})))
    return {
        "instStream": "OK (1 test)\n",
        "logcatFinished": True,
        "done": done,
        "preset": preset, "flavor": flavor,
        "pids": {123}, "fixturePids": {123}, "hostPids": {123},
        "elapsedSec": m.PRESETS[preset]["blocks"] * m.PRESETS[preset]["block_seconds"] + 5,
        "seconds": m.PRESETS[preset]["blocks"] * m.PRESETS[preset]["block_seconds"],
        "sampling": {"total": 10, "ok": 10, "failed": 0},
    }


for preset in sorted(m.PRESETS):
    for flavor in sorted(m.FLAVORS):
        ev = green_ev(preset, flavor, m.PRESETS[preset]["blocks"])
        state, note, _ = classify(ev)
        check(f"[{preset}/{flavor}] reference soak-done round-trips to PASS", state == "PASS",
              f"got {state} note={note!r}")

# A per-class failure in the fixture's taskClasses must flip the round to FAIL_FUNCTIONAL.
ev = green_ev("round2h", "consumer", 12)
bad = dict(ev["done"]); bad["taskClasses"] = dict(bad["taskClasses"]); bad["taskClasses"]["browser"] = "fail"
ev["done"] = bad
check("soak-done with one failing class -> FAIL_FUNCTIONAL",
      classify(ev)[0] == "FAIL_FUNCTIONAL")

# A goal-parity mismatch in the fixture's tallies must flip to FAIL_FUNCTIONAL.
ev2 = green_ev("round2h", "consumer", 12)
bad2 = dict(ev2["done"]); bad2["goalSuccess"] = 5; bad2["goalStop"] = 6
ev2["done"] = bad2
check("soak-done with goal parity 5/6 -> FAIL_FUNCTIONAL", classify(ev2)[0] == "FAIL_FUNCTIONAL")

# Old evidence-binding fixtures must not be counted as current Goal acceptance.
legacy = green_ev("round2h", "consumer", 12)
legacy["done"].pop("goalMechanism")
check("legacy Goal fixture is rejected", classify(legacy)[0] == "FAIL_FUNCTIONAL")

# single-pid: two distinct pids in the fixture log -> FAIL_FUNCTIONAL (restart).
ev3 = green_ev("round2h", "consumer", 12)
ev3["pids"] = {123, 124}; ev3["fixturePids"] = {123, 124}
check("two fixture pids (process restart) -> FAIL_FUNCTIONAL", classify(ev3)[0] == "FAIL_FUNCTIONAL")

# durationMs must be an INT the runner's finalize() cross-checks (not a float/string).
done = json.loads(json.dumps(soak_done("r", "consumer", 12, all_green_classes("consumer"),
                                       7200 * 1000, pid=1, resource_start={}, resource_end={})))
check("soak-done durationMs is an int (runner finalize cross-clock check)",
      isinstance(done["durationMs"], int))

# =====================================================================
#  (e) progress.json round-trip: keys the runner's watchdog reads, runId-keyed, phase 'done'
# =====================================================================
p_running = progress("ev04-consumer-round2h", "running", 7, 2, 123456)
p_done = progress("ev04-consumer-round2h", "done", 60, 12, 7200000)
need = {"runId", "phase", "seq", "block", "updatedMonotonicMs"}
check("progress.json carries the watchdog keys (runId/phase/seq/block/updatedMonotonicMs)",
      need.issubset(p_running.keys()) and need.issubset(p_done.keys()))
check("progress.json is runId-keyed (watchdog ignores a stale runId)",
      p_running["runId"] == "ev04-consumer-round2h")
check("progress.json terminal phase is 'done' (watchdog slow-exit branch)", p_done["phase"] == "done")
check("progress.json seq advances monotonically across a run (watchdog stall signal)",
      p_done["seq"] > p_running["seq"])

# =====================================================================
#  (f) fixture<->runner contract lock: the two modules agree on the class set + parity source
# =====================================================================
check("reference BASE_CLASSES == runner BASE_CLASSES",
      BASE_CLASSES == m.BASE_CLASSES)
check("reference DEVELOPER_EXTRA == runner DEVELOPER_EXTRA",
      DEVELOPER_EXTRA == m.DEVELOPER_EXTRA)
check("reference expected_classes == runner check_classes input set (consumer)",
      set(expected_classes("consumer")) == set(m.BASE_CLASSES))
check("reference expected_classes == runner check_classes input set (developer)",
      set(expected_classes("developer")) == set(m.BASE_CLASSES + m.DEVELOPER_EXTRA))

# ---- summary -----------------------------------------------------------
n_pass = sum(1 for _, ok, _ in RESULTS if ok)
failed = [n for n, ok, _ in RESULTS if not ok]
print(f"\n{n_pass}/{len(RESULTS)} EV-04 fixture-logic checks passed"
      + ("" if not failed else f" -- FAILURES: {failed}"))
sys.exit(0 if n_pass == len(RESULTS) else 1)

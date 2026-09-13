#!/usr/bin/env python3
"""Host-logic fault test for the EV-03 / HXA-103 runner (scripts/run-hxa103-browser-soak.py).

Device-independent and deterministic: it exercises the runner's PURE forensics-first judgment
(`classify`) + presets/gates against synthetic evidence, so the decision logic is verified without an
emulator (and without touching the two live EV-02 soaks). Run with:  python3 scripts/test-hxa103-runner-logic.py
"""
import importlib.util
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ev03", HERE / "run-hxa103-browser-soak.py")
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

GREEN = "OK (1 test)"
FAIL = "FAILURES!!!\njava.lang.AssertionError: cumulative PSS drift: 140000 -> 250000"


def base(**kw):
    ev = {"instStream": GREEN, "fixturePids": {123}, "hostPids": {123},
          "seconds": 86400, "elapsedSec": 86405, "preset": "p24h", "cycles": 600,
          "sampling": {"total": 2880, "ok": 2880, "failed": 0}, "testRunnerFinished": True}
    ev.update(kw)
    return ev


CASES = [
    # (label, evidence, expected_state)
    ("24h fully green -> PASS",
     base(), "PASS"),
    ("pilot green -> PASS (harness validation, not 24h)",
     base(preset="pilot", seconds=900, elapsedSec=905, cycles=12,
          sampling={"total": 30, "ok": 28, "failed": 2}), "PASS"),
    ("pilot green note must NOT claim a 24h result",
     base(preset="pilot", seconds=900, elapsedSec=905, cycles=12), "PASS"),
    ("PSS peak gate drift -> FAIL_RESOURCE",
     base(instStream=FAIL, fixturePids={123}, elapsedSec=5000), "FAIL_RESOURCE"),
    ("FD peak gate drift -> FAIL_RESOURCE",
     base(instStream="FAILURES!!!\ncumulative FD drift: 250 -> 262"), "FAIL_RESOURCE"),
    ("thread peak gate drift -> FAIL_RESOURCE",
     base(instStream="FAILURES!!!\ncumulative thread drift: 57 -> 76"), "FAIL_RESOURCE"),
    ("am-instrument FAILURES (functional) -> FAIL_FUNCTIONAL",
     base(instStream="FAILURES!!!\njava.lang.AssertionError: nested fixtures failed: ...",
          fixturePids={123}), "FAIL_FUNCTIONAL"),
    ("stream not OK -> FAIL_FUNCTIONAL",
     base(instStream="INSTRUMENTATION_CODE=0"), "FAIL_FUNCTIONAL"),
    ("target process restarted -> FAIL_FUNCTIONAL",
     base(fixturePids={123, 124}, hostPids={123, 124}), "FAIL_FUNCTIONAL"),
    ("host sampling gap -> INFRA_INTERRUPTED",
     base(instStream="", infraError="RuntimeError: host suspended / sampling gap 120s > 90s",
          fixturePids=set()), "INFRA_INTERRUPTED"),
    ("instrumentation timeout -> INFRA_INTERRUPTED",
     base(instStream="", infraError="TimeoutError: instrumentation exceeded 86400+180s without finishing",
          fixturePids=set()), "INFRA_INTERRUPTED"),
    ("no pid evidence -> INCONCLUSIVE",
     base(fixturePids=set(), hostPids=set(), seconds=900, elapsedSec=905,
          sampling={"total": 30, "ok": 5, "failed": 25}), "INCONCLUSIVE"),
    ("stream OK but duration not met -> INCONCLUSIVE",
     base(seconds=86400, elapsedSec=300), "INCONCLUSIVE"),
    ("cancelled cleanly -> CANCELLED",
     base(cancelled=True, instStream="", fixturePids=set(), elapsedSec=100), "CANCELLED"),
    # edge cases
    ("host pids only (fixture log absent) still confirms single-pid -> PASS",
     base(fixturePids=set(), hostPids={123}, seconds=900, elapsedSec=905,
          sampling={"total": 30, "ok": 30, "failed": 0}), "PASS"),
    ("elapsed == seconds boundary (not <) -> PASS",
     base(seconds=86400, elapsedSec=86400), "PASS"),
    ("no host samples at all, green stream -> PASS (fixture gate authoritative)",
     base(sampling={"total": 0, "ok": 0, "failed": 0}), "PASS"),
    ("sampling failures attributed but not disqualifying -> PASS",
     base(sampling={"total": 2880, "ok": 2700, "failed": 180}), "PASS"),
]

FAILURES = []
for label, ev, want in CASES:
    state, note, attribution = m.classify(ev)
    ok = state == want
    if not ok:
        FAILURES.append(label)
    print(f"[{'ok ' if ok else 'FAIL'}] {label}: got {state} (want {want})"
          + ("" if ok else f"  note={note!r}"))

# Presets / gates are fixed contract values (master-plan:56) -- a drift here breaks the run contract.
contract = [
    ("PRESETS pilot", m.PRESETS.get("pilot") == 900),
    ("PRESETS precheck", m.PRESETS.get("precheck") == 7200),
    ("PRESETS p24h", m.PRESETS.get("p24h") == 86400),
    ("GATES FD +8", m.GATES.get("FD") == 8),
    ("GATES threads +16", m.GATES.get("thread") == 16),
    ("GATES PSS +96MiB", m.GATES.get("PSS") == 96 * 1024),
    ("app workload test class", "ContinuousAppResourceDeviceTest" in m.WORKLOADS["app"]["test"]),
    ("app workload runner", "com.helix.app.HelixAndroidJUnitRunner" in m.WORKLOADS["app"]["component"]),
]
for label, ok in contract:
    if not ok:
        FAILURES.append(label)
    print(f"[{'ok ' if ok else 'FAIL'}] {label}")

total = len(CASES) + len(contract)
print(f"\n{total - len(FAILURES)}/{total} host-logic checks passed"
      + ("" if not FAILURES else f" -- FAILURES: {FAILURES}"))
sys.exit(1 if FAILURES else 0)

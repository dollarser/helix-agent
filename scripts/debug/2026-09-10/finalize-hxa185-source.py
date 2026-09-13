"""One-time manual-review refinements; no compilation, test invocation or device access."""
from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/diagnostics/DescriptorPhaseProbeDeviceTest.kt');s=p.read_text()
s=s.replace('        require(iterations in 1..60)','''        require(iterations in 1..60)
        val slotSeconds = (args.getString("helix.fd.slotSeconds") ?: "30").toLong()
        require(slotSeconds in 1..300)''')
s=s.replace('        repeat(iterations) { cycle ->','        repeat(iterations) { cycle ->\n            val slotStart = SystemClock.elapsedRealtime()')
s=s.replace('''            snapshot(file, runId, workload, "idle-1s", cycle)
        }''','''            snapshot(file, runId, workload, "idle-1s", cycle)
            val remaining = slotStart + slotSeconds * 1000 - SystemClock.elapsedRealtime()
            check(remaining >= 0) { "FD workload exceeded its frozen slot; restart with reviewed config" }
            SystemClock.sleep(remaining)
            snapshot(file, runId, workload, "slot-end", cycle)
        }''')
p.write_text(s)
p=Path('feature/browser/src/androidTest/kotlin/com/helix/feature/browser/BrowserAutofillSoakDeviceTest.kt');s=p.read_text().replace('''        if (local % cfg.recreateEvery == 0) {
            fixture.scenario.recreate()''','''        if (local % cfg.recreateEvery == 0) {
            diagnosticStage = "recreate"
            fixture.scenario.recreate()''');p.write_text(s)
p=Path('scripts/test-run-ev04-mainapp-soak-fixture-logic.py');s=p.read_text();needle='# single-pid: two distinct pids'
assert needle in s;s=s.replace(needle,'''# Old evidence-binding fixtures must not be counted as current Goal acceptance.
legacy = green_ev("round2h", "consumer", 12)
legacy["done"].pop("goalMechanism")
check("legacy Goal fixture is rejected", classify(legacy)[0] == "FAIL_FUNCTIONAL")

'''+needle);p.write_text(s)

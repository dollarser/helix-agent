from pathlib import Path
root=Path('runtime/proot-app/src/debug/kotlin/com/helix/runtime/proot/app')
p=root/'PtyNativeJourney.kt'
s=p.read_text().replace('private fun startInteractive()', 'fun startInteractive()')
p.write_text(s)
p=root/'PtyNativeProbeService.kt'
s=p.read_text().replace('1..6', '1..7').replace('6 -> probe.prootClosure(quit = true)', '6 -> probe.prootClosure(quit = true)\n                7 -> PtySessionWorkerJourney(this).run(probe::startInteractive)')
p.write_text(s)

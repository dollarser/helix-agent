"""Move the existing orphan sweep unchanged except its explicit dependencies."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
source = root / 'runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotJobRunner.kt'
text = source.read_text()
start = text.index('    /**\n     * Orphan sweep (service (re)start')
end = text.index('\n}\n\nprivate fun sha256Of', start)
block = text[start:end]
block = '\n'.join(line[4:] if line.startswith('    ') else line for line in block.splitlines())
block = block.replace('fun sweepOrphans() {', 'internal fun sweepProotOrphans(store: ProotJobStore, kill: (Int) -> Unit) {')
block = block.replace('killProcessGroup(pid)', 'kill(pid)')
target = source.with_name('ProotOrphanSweep.kt')
assert not target.exists()
target.write_text('package com.helix.runtime.proot.app\n\nimport com.helix.runtime.proot.ipc.ProotJobState\nimport java.io.File\n\n' + block + '\n')
source.write_text(text[:start] + '    fun sweepOrphans() = sweepProotOrphans(store, ::killProcessGroup)\n' + text[end:])

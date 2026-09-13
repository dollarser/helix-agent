"""One-time HXA-187 test-host wiring; preserves every original assertion."""
from pathlib import Path
root = Path('runtime/quickjs/src/androidTest/kotlin/com/helix/runtime/quickjs')
for p in root.glob('*Test.kt'):
    s = p.read_text()
    s = s.replace(f'class {p.stem} {{', f'class {p.stem} : QuickJsDeviceTestHost() {{')
    if p.stem == 'JsAttackE2eTest':
        start = s.index('        val diagnosticDone =')
        end = s.index('        // The §4.1 DEFAULTS', start)
        s = s[:start] + s[end:]
        s = s.replace('        diagnosticDone.countDown()\n', '')
    p.write_text(s)

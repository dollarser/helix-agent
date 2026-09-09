from pathlib import Path
p=Path('scripts/debug/2026-09-09/run-owned-emulator.py')
s=p.read_text().replace('"TestRunner", "System.out"','"TestRunner", "System.out", "HelixChat"')
p.write_text(s)

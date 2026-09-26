"""Use the owned runner, restricting boot diagnostics to the main log buffer.

API36 all-buffer boot logcat exited 255 while reading kernel logs before tests.
Instrumentation, skip evidence, failure detection and owned cleanup are unchanged.
"""
from pathlib import Path

runner = Path(__file__).resolve().parents[2] / "run-owned-acceptance-emulator.py"
source = runner.read_text()
original = 'device("logcat", "-d", "-t", "20000")'
assert source.count(original) == 1
source = source.replace(original, 'device("logcat", "-b", "main", "-d", "-t", "2000")')
exec(compile(source, str(runner), "exec"), {"__file__": str(runner), "__name__": "__main__"})

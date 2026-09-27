"""Run the two bounded Workspace follow-ups under the owning runner's live emulator."""
from pathlib import Path
import subprocess
import sys

directory = Path(__file__).resolve().parent
for script in ('verify-hxa210-cleanup-widths.py', 'verify-hxa210-recovery-cuts.py'):
    subprocess.run([sys.executable, str(directory / script), *sys.argv[1:]], check=True, timeout=600)

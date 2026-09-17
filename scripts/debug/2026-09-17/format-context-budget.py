#!/usr/bin/env python3
"""Format only Kotlin files changed in this isolated worktree."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]
paths = subprocess.check_output(['git', 'diff', '--name-only'], cwd=root, text=True).splitlines()
paths += subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard'], cwd=root, text=True).splitlines()
files = sorted({str(root / p) for p in paths if p.endswith('.kt')})
subprocess.run(['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(files), '--console=plain'], cwd=root, check=True)

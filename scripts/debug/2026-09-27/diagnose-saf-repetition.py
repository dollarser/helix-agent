"""Bounded reproduction only: retain each attempt and stop when diagnostics reproduce."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

serial, destination = sys.argv[1:]
output = Path(destination)
classes = output / 'saf-class.txt'
classes.write_text('com.helix.app.ui.FilesImportExportUiTest\n')
for attempt in range(1, 4):
    child = output / f'attempt-{attempt}'
    child.mkdir()
    shutil.copyfile(output / 'owner.json', child / 'owner.json')
    subprocess.run([sys.executable, 'scripts/debug/2026-09-27/verify-hxa227-full-baseline.py',
                    serial, str(child)], env=dict(os.environ, HXA227_CLASS_LIST=str(classes)), check=True)
    rows = [json.loads(path.read_text()) for path in (child / 'baseline/results').glob('*.json')]
    assert len(rows) == 1
    print(f'Attempt {attempt}: {rows[0]["verdict"]}', flush=True)
    if rows[0]['verdict'] != 'PASS':
        raise RuntimeError('Diagnostic failure reproduced; inspect retained raw results')
print('No reproduction in three attempts; this is not evidence that the defect was fixed', flush=True)

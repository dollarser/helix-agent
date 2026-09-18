"""Compare captured APK entry contents when a later Gradle packaging changes the zip hash."""
import hashlib
from pathlib import Path
import zipfile
import os
import subprocess
import re

root = Path(__file__).resolve().parents[3]
old = root / 'build/hxa196-core-api29-03/app.apk'
new = root / 'build/hxa196-regression-api36/app.apk'
with zipfile.ZipFile(old) as left, zipfile.ZipFile(new) as right:
    names = sorted(set(left.namelist()) | set(right.namelist()))
    changed = [name for name in names if name not in left.namelist() or name not in right.namelist()
               or hashlib.sha256(left.read(name)).digest() != hashlib.sha256(right.read(name)).digest()]
    print('Changed entries:', changed)
    destination = root / 'build/hxa196-apk-diff'
    destination.mkdir(exist_ok=True)
    for label, archive in [('old', left), ('new', right)]:
        for name in changed:
            path = destination / (label + '-' + name)
            path.write_bytes(archive.read(name))
            if name.endswith('.dex'):
                dump = Path(os.environ['ANDROID_HOME']) / 'build-tools/36.0.0/dexdump'
                with path.with_suffix('.txt').open('w') as output:
                    subprocess.run([str(dump), '-d', str(path)], stdout=output, check=True)
    assert changed == ['classes6.dex'], changed
    def instructions(label):
        lines = (destination / (label + '-classes6.txt')).read_text(encoding='latin-1').splitlines()[2:]
        return [re.sub(r'line=\d+', 'line=SOURCE', line) for line in lines]
    assert instructions('old') == instructions('new'), 'Executable DEX or metadata differs'
    print('All APK entries match except DEX source line positions; disassembly and metadata match.')

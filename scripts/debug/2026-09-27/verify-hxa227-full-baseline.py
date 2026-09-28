"""Run the existing isolated class baseline only on this task's owned emulator."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import shutil
import hashlib

serial, destination = sys.argv[1:]
output = Path(destination)
root = Path(__file__).resolve().parents[3]
owner = json.loads((output / 'owner.json').read_text())
assert owner['serial'] == serial and owner['avd'] == 'Helix_HXA210_API36'
os.kill(owner['pid'], 0)
sys.path.insert(0, str(root / 'scripts'))
from agent_eval import source_manifest, write_json
write_json(output / 'device-source-manifest.json', source_manifest(root,
           ['app/src', 'core', 'provider', 'tools', 'runtime', 'feature', 'gradle', 'buildSrc',
            'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']))
classes = Path(os.environ.get('HXA227_CLASS_LIST', str(root / 'build/hxa227/current-consumer-classes.txt')))
shutil.copyfile(classes, output / 'current-consumer-classes.txt')
names = classes.read_text().splitlines()
write_json(output / 'current-consumer-manifest.json', {
    'classes': names, 'class_count': len(names),
    'class_list_sha256': hashlib.sha256(classes.read_bytes()).hexdigest(),
})
provenance = {}
for relative in ('scripts/debug/2026-09-26/run-isolated.py',
                 'scripts/debug/2026-09-26/summarize-current-device-baseline.py',
                 'scripts/debug/2026-09-27/verify-hxa227-full-baseline.py',
                 'scripts/run-owned-emulator.py', 'scripts/instrumentation_junit.py'):
    source = root / relative
    archived = output / 'runner-source' / relative
    archived.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, archived)
    provenance[relative] = hashlib.sha256(source.read_bytes()).hexdigest()
write_json(output / 'runner-provenance.json', provenance)
path = root / 'scripts/debug/2026-09-26/run-isolated.py'
spec = importlib.util.spec_from_file_location('isolated_baseline', path)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
original = runner.run_cmd


def owned_command(*args, **kwargs):
    os.kill(owner['pid'], 0)
    return original(*args, **kwargs)


runner.run_cmd = owned_command
sys.argv = [str(path), str(output / 'current-consumer-classes.txt'),
            '--manifest', str(output / 'current-consumer-manifest.json'),
            '--out-dir', str(output / 'baseline'), '--serial', serial, '--flavor', 'consumer', '--timeout', '180']
runner.main()

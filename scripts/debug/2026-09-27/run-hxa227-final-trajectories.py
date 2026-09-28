"""Sequential owner-authorized emulator matrix; stop at the first failed run."""
import json
import argparse
import re
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / 'scripts'))
from agent_eval import source_manifest, write_json
from instrumentation_junit import parse

parser = argparse.ArgumentParser()
parser.add_argument('--run-id', required=True)
args = parser.parse_args()
assert re.fullmatch(r'[a-z0-9-]+', args.run_id), 'Use a lowercase run identity'
matrix = [('consumer', 29, 5656), ('developer', 29, 5658),
          ('consumer', 36, 5660), ('developer', 36, 5662)]
names = [f'{flavor}-final-api{api}-{args.run_id}' for flavor, api, _ in matrix]
assert all(not (root / 'build/hxa227/device' / name).exists() for name in names)
summary_path = root / f'build/hxa227/device-trajectory-{args.run_id}.json'
assert not summary_path.exists()

manifest = json.loads((root / 'evals/trajectory/core-device.json').read_text())
selectors = ','.join(ref['class'] + '#' + ref['method'] for case in manifest['cases'] for ref in case['tests'])
write_json(root / 'build/hxa227/device-source-manifest.json', source_manifest(root,
           ['app/src', 'core', 'provider', 'tools', 'runtime', 'feature', 'gradle', 'buildSrc',
            'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties']))
env = dict(os.environ, ANDROID_HOME=str(Path.home() / 'Library/Android/sdk'))
runs = []
for flavor, api, port in matrix:
    name = f'{flavor}-final-api{api}-{args.run_id}'
    output = root / 'build/hxa227/device' / name
    package = 'com.helix.agent' + ('.developer' if flavor == 'developer' else '')
    command = [sys.executable, 'scripts/run-owned-emulator.py', '--avd', f'Helix_HXA210_API{api}',
               '--port', str(port), '--density-dpi', '400',
               '--apk', f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk',
               '--test-apk', f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk',
               '--runner', package + '.test/com.helix.app.HelixAndroidJUnitRunner',
               '--classes', selectors, '--raw-results', '--output', str(output), '--timeout', '900']
    print('Starting ' + name, flush=True)
    with (root / 'build/hxa227' / (name + '.log')).open('w') as log:
        subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    subprocess.run([sys.executable, 'scripts/debug/2026-09-27/summarize-hxa227-device.py', str(output)], cwd=root, check=True)
    records = parse((output / 'instrumentation.txt').read_text())
    assert len(records) == 10 and all(value[0] == 0 for value in records.values())
    assert json.loads((output / 'closed.json').read_text())['exit'] == 0
    runs.append({'run': name, 'methods': 10, 'cases': 8, 'result': 'passed',
                 'artifacts': json.loads((output / 'artifacts.json').read_text()),
                 'context': json.loads((output / 'context.json').read_text())})
    print('Passed ' + name, flush=True)
for flavor in ('consumer', 'developer'):
    artifacts = [run['artifacts'] for run in runs if run['context']['flavor'] == flavor]
    assert artifacts[0] == artifacts[1]
write_json(summary_path, {'methods': 40, 'cases': 32, 'runs': runs})

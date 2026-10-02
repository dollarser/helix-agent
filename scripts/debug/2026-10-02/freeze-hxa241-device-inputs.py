#!/usr/bin/env python3
"""Record or recheck the exact changed code and APK inputs for HXA-241 acceptance."""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[3]
RECORD = ROOT / 'build/hxa241/emulator-20261002/final-source-identity.json'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def snapshot():
    raw = subprocess.check_output(['git', 'ls-files', '-z', '--modified', '--others', '--exclude-standard', '--',
                                   'app', 'core', 'feature', 'provider', 'runtime', 'tools', 'extensions',
                                   'build.gradle.kts', 'settings.gradle.kts'], cwd=ROOT)
    names = sorted(set(name.decode() for name in raw.split(b'\0') if name))
    files = {name: digest(ROOT / name) for name in names if (ROOT / name).is_file()}
    apks = {}
    for flavor in ('consumer', 'developer'):
        for kind in ('app', 'test'):
            path = ROOT / (f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk' if kind == 'app' else
                           f'app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk')
            apks[f'{flavor}-{kind}'] = {'sha256': digest(path), 'bytes': path.stat().st_size}
    return {'head': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'changed_code': files, 'apks': apks,
            'base_prompt': digest(ROOT / 'app/src/main/resources/prompts/base.md')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('record', 'verify'))
    args = parser.parse_args()
    current = snapshot()
    if args.action == 'record':
        with RECORD.open('x') as stream:
            json.dump(current, stream, indent=2)
            stream.write('\n')
    else:
        expected = json.loads(RECORD.read_text())
        if expected != current:
            changed = [name for name in set(expected['changed_code']) | set(current['changed_code'])
                       if expected['changed_code'].get(name) != current['changed_code'].get(name)]
            raise RuntimeError('Acceptance code/APKs changed after freeze: ' + ', '.join(sorted(changed)))
    print(json.dumps({'action': args.action, 'code_files': len(current['changed_code']), 'head': current['head'],
                      'apks': current['apks'], 'base_prompt': current['base_prompt']}, indent=2))


if __name__ == '__main__':
    main()

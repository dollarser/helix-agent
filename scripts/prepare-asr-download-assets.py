#!/usr/bin/env python3
"""Package only a pinned download descriptor/licenses; weights are AndroidTest fixtures, never main assets."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'build/asr-download'
LOCK = ROOT / 'config/asr/model.lock.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs():
    return {'lock': sha(LOCK), 'preparedAsr': sha(ROOT / 'build/asr/manifest.json'),
            'recipe': sha(Path(__file__))}


def assert_no_weights(assets):
    for path in assets.rglob('*'):
        if path.is_file() and not (path.relative_to(assets).as_posix().startswith('asr-licenses/') or
                                   path.relative_to(assets).as_posix() == 'asr-model-lock.json'):
            raise ValueError('Unexpected production ASR asset: ' + path.name)


def prepare():
    spec = importlib.util.spec_from_file_location('asr', ROOT / 'scripts/build-asr-assets.py')
    asr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(asr)
    asr.verify()
    assets = OUTPUT / 'assets'
    if assets.exists(): shutil.rmtree(assets)
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / 'build/asr/assets/asr-licenses', assets / 'asr-licenses', dirs_exist_ok=True)
    shutil.copyfile(LOCK, assets / 'asr-model-lock.json')
    fixture = OUTPUT / 'test-assets/asr-model'
    if fixture.exists(): shutil.rmtree(fixture)
    fixture.mkdir(parents=True, exist_ok=True)
    lock = json.loads(LOCK.read_text())
    if lock['license'] != 'Apache-2.0': raise ValueError('Review the model license before preparation')
    with (assets / 'asr-licenses/SOURCES.txt').open('a') as notices:
        notices.write('Selected ASR model: ' + lock['model'] + '\nSource: ' + lock['source'] +
                      '\nLicense: Apache-2.0, as declared by its model card; Xiaomi AI Lab Next-gen-Kaldi team.\n' +
                      'The upstream zipformer Python implementation is not included.\n')
    shutil.copyfile(assets / 'asr-licenses/sherpa-Apache-2.0.txt', assets / 'asr-licenses/model-Apache-2.0.txt')
    downloads = OUTPUT / 'models'
    downloads.mkdir(parents=True, exist_ok=True)
    for name, row in lock['files'].items():
        if name not in {'encoder.fp16.onnx', 'decoder.onnx', 'joiner.fp16.onnx', 'tokens.txt'}:
            raise ValueError('Unexpected model file')
        source = downloads / name
        if not asr.matches(source, row['sha256'], row['size']):
            asr.fetch(row['url'], source, row['sha256'], row['size'])
        if source.stat().st_size != row['size'] or sha(source) != row['sha256']:
            raise ValueError('Model input differs: ' + name)
        shutil.copyfile(source, fixture / name)
    assert_no_weights(assets)
    files = [p for folder in ['assets', 'test-assets'] for p in (OUTPUT / folder).rglob('*') if p.is_file()]
    record = {'inputs': inputs(), 'outputs': {str(p.relative_to(OUTPUT)): {'size': p.stat().st_size,
              'sha256': sha(p)} for p in files}}
    (OUTPUT / 'manifest.json').write_text(json.dumps(record, indent=2) + '\n')
    verify()


def verify():
    record = json.loads((OUTPUT / 'manifest.json').read_text())
    if record['inputs'] != inputs():
        raise ValueError('Run python3 scripts/prepare-asr-download-assets.py')
    assert_no_weights(OUTPUT / 'assets')
    for name, row in record['outputs'].items():
        relative = Path(name)
        if relative.is_absolute() or '..' in relative.parts:
            raise ValueError('Invalid prepared asset path')
        path = OUTPUT / relative
        if path.stat().st_size != row['size'] or sha(path) != row['sha256']:
            raise ValueError('ASR download asset mismatch: ' + name)
    print('ASR download descriptor/licenses verified; no production model weights')


def verify_apk(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        forbidden = {'encoder.int8.onnx', 'encoder.fp16.onnx', 'decoder.onnx', 'joiner.int8.onnx', 'joiner.fp16.onnx', 'model.int8.onnx'}
        if any(name.startswith(('assets/asr/', 'assets/asr-model/')) or
               (name.startswith('assets/') and Path(name).name in forbidden) for name in names):
            raise ValueError('Production APK contains offline ASR weights')
        if json.loads(archive.read('assets/asr-model-lock.json')) != json.loads(LOCK.read_text()):
            raise ValueError('Wrong ASR download descriptor')
        for suffix in ['/libsherpa-onnx-jni.so', '/libonnxruntime.so', 'assets/asr-licenses/SOURCES.txt']:
            if not any(name.endswith(suffix) for name in names):
                raise ValueError('Missing offline runtime or attribution: ' + suffix)
    print('Production ASR descriptor/runtime present; model weights absent: ' + Path(path).name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--apk', action='append')
    args = parser.parse_args()
    if args.apk:
        for apk in args.apk:
            verify_apk(apk)
    else:
        verify() if args.verify else prepare()

#!/usr/bin/env python3
"""Prepare/verify the fixed MIT Silero VAD model, reusing the existing speech runtime."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import zipfile
import re

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / 'config/vad/model.lock.json'
LICENSE = ROOT / 'config/vad/LICENSE-MIT.txt'
OUTPUT = ROOT / 'build/vad/assets/voice-vad'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def specification():
    row = json.loads(LOCK.read_text())
    if not re.fullmatch('[0-9a-f]{64}', row['sha256']) or not 100_000 <= row['size'] <= 5_000_000:
        raise ValueError('Invalid bounded VAD model lock')
    if row['license'] != 'MIT' or digest(LICENSE) != row['licenseSha256']:
        raise ValueError('VAD license differs from reviewed lock')
    return row


def verify():
    row = specification()
    model = OUTPUT / 'silero-v5.onnx'
    if model.stat().st_size != row['size'] or digest(model) != row['sha256']:
        raise ValueError('VAD model differs from lock')
    if digest(OUTPUT / 'LICENSE-MIT.txt') != row['licenseSha256']:
        raise ValueError('VAD license missing or changed')
    if json.loads((OUTPUT / 'model.lock.json').read_text()) != row:
        raise ValueError('VAD attribution/lock differs')
    print('Fixed Silero VAD model and MIT attribution verified')


def prepare():
    row = specification()
    spec = importlib.util.spec_from_file_location('locked_fetch', ROOT / 'scripts/ci/fetch_locked_asset.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    model = OUTPUT / 'silero-v5.onnx'
    if not module.matches(model, row['sha256'], row['size']):
        module.fetch(row['url'], model, row['sha256'], row['size'])
    shutil.copyfile(LICENSE, OUTPUT / 'LICENSE-MIT.txt')
    shutil.copyfile(LOCK, OUTPUT / 'model.lock.json')
    verify()


def verify_apk(path):
    row = specification()
    with zipfile.ZipFile(path) as archive:
        model = archive.read('assets/voice-vad/silero-v5.onnx')
        if len(model) != row['size'] or hashlib.sha256(model).hexdigest() != row['sha256']:
            raise ValueError('APK VAD differs from lock')
        if hashlib.sha256(archive.read('assets/voice-vad/LICENSE-MIT.txt')).hexdigest() != row['licenseSha256']:
            raise ValueError('APK VAD license missing or changed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    parser.add_argument('--verify-lock', action='store_true')
    parser.add_argument('--apk', type=Path)
    args = parser.parse_args()
    if args.verify_lock:
        specification()
    elif args.apk:
        verify_apk(args.apk)
    elif args.verify:
        verify()
    else:
        prepare()

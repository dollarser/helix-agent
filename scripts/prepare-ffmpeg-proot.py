#!/usr/bin/env python3
"""Verify the pinned Android FFmpeg closure and package it for the existing Advanced PRoot lane."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import tempfile
import zipfile

SHA256 = '7fc77f64f27bcd56e1283b9e1233cc743da7b9ed3015accecd7ad7366bef2185'
LIBRARIES = {'libavcodec.so', 'libavformat.so', 'libavfilter.so', 'libavutil.so', 'libswscale.so', 'libswresample.so'}
FILES = LIBRARIES | {'ffmpeg', 'ffprobe'}
LIMIT = 25_000_000


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def validated_entries(candidate: Path) -> tuple[dict[str, bytes], dict]:
    require(candidate.is_file() and not candidate.is_symlink(), 'Missing pinned FFmpeg candidate')
    require(candidate.stat().st_size <= 8_000_000, 'Candidate archive exceeds bound')
    require(digest(candidate.read_bytes()) == SHA256, 'FFmpeg candidate SHA mismatch; never substitute another build')
    with zipfile.ZipFile(candidate) as bundle:
        names = bundle.namelist()
        require(len(names) == len(set(names)) and len(names) < 256, 'Invalid entry inventory')
        require(sum(info.file_size for info in bundle.infolist()) < 32_000_000, 'Expanded candidate exceeds bound')
        for name in names:
            path = PurePosixPath(name)
            require(not path.is_absolute() and '..' not in path.parts and '\\' not in name, 'Unsafe candidate path')
            require(path.suffix.lower() not in {'.ttf', '.otf', '.ttc', '.woff', '.woff2'}, 'Font distribution is not permitted')
        inventory = json.loads(bundle.read('MANIFEST.json'))
        require(set(names) == set(inventory) | {'MANIFEST.json'}, 'Candidate manifest mismatch')
        data = {name: bundle.read(name) for name in names if name != 'MANIFEST.json'}
        for name, record in inventory.items():
            require(len(data[name]) == record['bytes'] and digest(data[name]) == record['sha256'], 'Changed candidate entry')
        manifest = json.loads(data['metadata/candidate.json'])
        require(manifest['profile'] == 'ready-av1' and manifest['target'] == 'arm64-v8a', 'Wrong candidate platform/profile')
        require({row['name'] for row in manifest['files']} == FILES, 'Unexpected native dependency closure')
        require(sum(row['bytes'] for row in manifest['files']) == manifest['runtime_bytes'] < LIMIT, 'Native payload exceeds 25MB')
        result = {}
        for row in manifest['files']:
            name = row['name']
            entry = f"{'lib' if name.endswith('.so') else 'tools'}/arm64-v8a/{name}"
            require(digest(data[entry]) == row['sha256'] and len(data[entry]) == row['bytes'], 'Wrong runtime bytes')
            output = name if name.endswith('.so') else f'libhelix_{name}.so'
            result[f'jniLibs/arm64-v8a/{output}'] = data[entry]
        for name in data:
            if name.startswith('LICENSES/'):
                result['assets/runtime/media/' + name] = data[name]
        for src, dst in {'metadata/candidate.json': 'candidate.json', 'metadata/input-sources.json': 'sources.json',
                         'NOTICE.txt': 'NOTICE.txt', 'scripts/debug/2026-10-01/ffmpeg-ready/recipe.json': 'recipe.json'}.items():
            result['assets/runtime/media/' + dst] = data[src]
        return result, manifest


def prepare(candidate: Path, destination: Path) -> dict:
    entries, manifest = validated_entries(candidate)
    destination.parent.mkdir(parents=True, exist_ok=True)
    require(not destination.is_symlink(), 'Output cannot be a symlink')
    staging = Path(tempfile.mkdtemp(prefix='ffmpeg-stage-', dir=destination.parent))
    backup = destination.with_name(destination.name + '.previous')
    require(not backup.exists(), 'Previous interrupted extraction requires review')
    try:
        for name, data in entries.items():
            file = staging / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_bytes(data)
        if destination.exists():
            destination.rename(backup)
        try:
            staging.rename(destination)
        except BaseException:
            if backup.exists():
                backup.rename(destination)
            raise
        if backup.exists():
            shutil.rmtree(backup)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return {'runtime_bytes': manifest['runtime_bytes'], 'archive_sha256': SHA256,
            'files': len(entries), 'device_tested': False, 'route': 'existing-proot-job-bionic-cli-bridge'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.candidate, args.destination)))

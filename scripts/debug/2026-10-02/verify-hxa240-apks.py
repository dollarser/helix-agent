#!/usr/bin/env python3
"""Inspect actual APK payloads and compare same-worktree pre-integration baselines; no device use."""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/hxa240'
SDK = Path(os.environ.get('ANDROID_HOME', Path.home()/'Library/Android/sdk'))
READELF = SDK/'ndk/28.2.13676358/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf'
ANALYZER = SDK/'cmdline-tools/latest/bin/apkanalyzer'
CANDIDATE = ROOT/'runtime/proot-app/vendor/ffmpeg-9.0.2-ready-av1-arm64-v8a.zip'
EXPECTED_SHA = '7fc77f64f27bcd56e1283b9e1233cc743da7b9ed3015accecd7ad7366bef2185'


def require(value, reason):
    if not value:
        raise RuntimeError(reason)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    require(sha(CANDIDATE.read_bytes()) == EXPECTED_SHA, 'Candidate changed')
    with zipfile.ZipFile(CANDIDATE) as candidate:
        manifest = json.loads(candidate.read('metadata/candidate.json'))
        expected = {}
        for row in manifest['files']:
            name = row['name']
            packaged = name if name.endswith('.so') else 'libhelix_' + name + '.so'
            expected['lib/arm64-v8a/' + packaged] = row
    report = {'base_head': '5cfc9e2399d28f2131843ab9961a329cb5b0b59d', 'candidate_sha256': EXPECTED_SHA,
              'runtime_bytes': manifest['runtime_bytes'], 'limit_bytes': 25_000_000,
              'device_execution': 'not_requested', 'flavors': {}}
    for flavor in ('consumer', 'developer'):
        apk = ROOT/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
        baseline = OUT/f'baseline-{flavor}.apk'
        data = apk.read_bytes()
        xml = subprocess.check_output([str(ANALYZER), 'manifest', 'print', str(apk)], text=True)
        require('com.helix.runtime.media' not in xml and ':helix_media' not in xml, 'Retired media Service shipped')
        with zipfile.ZipFile(apk) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)), 'Duplicate APK entries')
            dex = b''.join(archive.read(n) for n in names if n.endswith('.dex'))
            require(b'Lcom/helix/runtime/media/' not in dex, 'Retired media classes shipped')
            native = [n for n in names if n.rsplit('/', 1)[-1].startswith(('libav', 'libsw', 'libhelix_ffmpeg', 'libhelix_ffprobe'))]
            if flavor == 'consumer':
                require(not native and not any(n.startswith('assets/runtime/media/') for n in names), 'Standard leaked FFmpeg')
            else:
                require(set(native) == set(expected), 'Missing, duplicated or extra FFmpeg ABI payload')
                require('assets/runtime/media/USAGE.md' in names, 'Missing native CLI usage')
                require('assets/runtime/media/LICENSES/dav1d/COPYING' in names, 'Missing dependency license')
                require('assets/runtime/media/NOTICE.txt' in names, 'Missing candidate notice')
                with tempfile.TemporaryDirectory() as directory:
                    for name, row in expected.items():
                        content = archive.read(name)
                        require(len(content) == row['bytes'] and sha(content) == row['sha256'], 'APK changed pinned ELF: ' + name)
                        file = Path(directory)/Path(name).name
                        file.write_bytes(content)
                        elf = subprocess.check_output([str(READELF), '-lW', str(file)], text=True)
                        loads = [int(line.split()[-1], 16) for line in elf.splitlines() if line.strip().startswith('LOAD ')]
                        require(loads and min(loads) >= 16384, 'ELF 16 KiB alignment mismatch')
            with zipfile.ZipFile(baseline) as before:
                old_names = set(before.namelist())
                added = sorted(set(names) - old_names)
                changed = sorted(n for n in set(names) & old_names if archive.getinfo(n).CRC != before.getinfo(n).CRC)
            delta = len(data) - baseline.stat().st_size
            require(delta < 25_000_000, 'Actual APK increment exceeds 25 MB')
            report['flavors'][flavor] = {'apk_bytes': len(data), 'apk_sha256': sha(data),
                'baseline_bytes': baseline.stat().st_size, 'baseline_sha256': sha(baseline.read_bytes()),
                'apk_increment_bytes': delta, 'ffmpeg_entries': native,
                'added_entries': added, 'changed_entries': changed}
    (OUT/'apk-media-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({**{k:v for k,v in report.items() if k != 'flavors'}, 'flavors': {
        k: {name:value for name,value in v.items() if name not in ('added_entries', 'changed_entries', 'ffmpeg_entries')}
        for k,v in report['flavors'].items()}}, indent=2))


if __name__ == '__main__':
    main()

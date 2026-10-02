#!/usr/bin/env python3
"""Fetch fixed upstream sources into an isolated size experiment; never changes the app or global tools."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import venv

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/ffmpeg-plus-size-2026-10-01'
SOURCES = {
    'lame': ('3.100', 'https://downloads.sourceforge.net/project/lame/lame/3.100/lame-3.100.tar.gz', None),
    'opus': ('1.6.1', 'https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz', '6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1'),
    'webp': ('1.6.0', 'https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz', None),
    'freetype': ('2.14.3', 'https://download.savannah.gnu.org/releases/freetype/freetype-2.14.3.tar.xz', None),
    'fribidi': ('1.0.16', 'https://github.com/fribidi/fribidi/releases/download/v1.0.16/fribidi-1.0.16.tar.xz', None),
    'harfbuzz': ('14.3.0', 'https://github.com/harfbuzz/harfbuzz/releases/download/14.3.0/harfbuzz-14.3.0.tar.xz', None),
    'unibreak': ('6.1', 'https://github.com/adah1972/libunibreak/releases/download/libunibreak_6_1/libunibreak-6.1.tar.gz', None),
    'ass': ('0.17.5', 'https://github.com/libass/libass/releases/download/0.17.5/libass-0.17.5.tar.xz', None),
}

# Fixed hashes observed from the named upstream release downloads; only Opus was also compared to its published checksum.
PINNED_SHA256 = {
    'lame': 'ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e',
    'opus': '6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1',
    'webp': 'e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564',
    'freetype': '36bc4f1cc413335368ee656c42afca65c5a3987e8768cc28cf11ba775e785a5f',
    'fribidi': '1b1cde5b235d40479e91be2f0e88a309e3214c8ab470ec8a2744d82a5a9ea05c',
    'harfbuzz': '16070d77cfc4ba1f1e7327e83bf9b3f55898081cabdb94e56a33e04fc8874eae',
    'unibreak': 'cc4de0099cf7ff05005ceabff4afed4c582a736abc38033e70fdac86335ce93f',
    'ass': '2dca25c0e0c837ddf00b52011b3f82cac1e4ddd3ad018227806b0c2288864acc',
}

def digest(file: Path) -> str:
    value = hashlib.sha256()
    with file.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()

def main() -> None:
    downloads = OUT / 'downloads'
    downloads.mkdir(parents=True, exist_ok=True)
    source = OUT / 'source'
    source.mkdir(exist_ok=True)
    lock_file = OUT / 'source-lock.json'
    locked = json.loads(lock_file.read_text()) if lock_file.exists() else {}
    for name, (version, url, known) in SOURCES.items():
        archive = downloads / url.rsplit('/', 1)[1]
        if not archive.exists():
            partial = archive.with_name(archive.name + '.partial')
            subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--tlsv1.2',
                            '--connect-timeout', '20', '--max-time', '180', '--retry', '2',
                            '--output', str(partial), url], check=True)
            partial.replace(archive)
        sha = digest(archive)
        expected = PINNED_SHA256[name]
        if sha != expected or (known is not None and sha != known):
            raise RuntimeError(f'Source checksum mismatch: {name}')
        target = source / name
        if not target.exists():
            staging = source / (name + '-extract')
            staging.mkdir(exist_ok=True)
            with tarfile.open(archive) as bundle:
                bundle.extractall(staging, filter='data')
            roots = list(staging.iterdir())
            if len(roots) != 1 or not roots[0].is_dir():
                raise RuntimeError(f'Unexpected archive layout: {name}')
            roots[0].rename(target)
            staging.rmdir()
        locked[name] = {'version': version, 'url': url, 'sha256': sha,
                        'verification': 'upstream published SHA256' if known else 'upstream HTTPS, first-download hash pinned; no signature claim'}
        lock_file.write_text(json.dumps(locked, indent=2) + '\n')
        print(json.dumps({'source': name, **locked[name]}), flush=True)
    tools = OUT / 'tools-venv'
    if not (tools / 'bin/python').exists():
        venv.EnvBuilder(with_pip=True).create(tools)
    subprocess.run([str(tools / 'bin/python'), '-m', 'pip', 'install', '--disable-pip-version-check',
                    'meson==1.9.1', 'ninja==1.13.0'], check=True)

if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Reuse raw downloads only after checking the committed size and SHA-256 lock."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile


def matches(path, sha256, size):
    if not path.is_file() or path.is_symlink() or path.stat().st_size != size:
        return False
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest() == sha256


def fetch(url, destination, sha256, size, cache=None):
    if not re.fullmatch('[0-9a-f]{64}', sha256) or size <= 0 or not url.startswith('https://'):
        raise ValueError('Expected HTTPS URL and a fixed positive size/SHA-256 lock')
    destination.parent.mkdir(parents=True, exist_ok=True)
    cached = cache / sha256 if cache else None
    if cached and matches(cached, sha256, size):
        shutil.copyfile(cached, destination)
        print('Locked download cache hit: ' + sha256)
        return
    with tempfile.TemporaryDirectory(dir=destination.parent) as directory:
        temporary = Path(directory) / 'download'
        subprocess.run(['curl', '--fail', '--location', '--proto', '=https', '--proto-redir', '=https',
                        '--retry', '3', '--max-time', '600', '--output', str(temporary), url], check=True)
        if not matches(temporary, sha256, size):
            raise ValueError('Downloaded asset does not match the content lock')
        if cached:
            cache.mkdir(parents=True, exist_ok=True)
            # The scratch file is uniquely owned; never publish a partial cache entry.
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as output:
                scratch = Path(output.name)
            try:
                shutil.copyfile(temporary, scratch)
                os.replace(scratch, cached)
            finally:
                scratch.unlink(missing_ok=True)
        os.replace(temporary, destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--size', required=True, type=int)
    parser.add_argument('--cache', type=Path)
    args = parser.parse_args()
    fetch(args.url, args.destination, args.sha256, args.size, args.cache)


if __name__ == '__main__':
    main()

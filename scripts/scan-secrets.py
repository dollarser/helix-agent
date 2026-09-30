#!/usr/bin/env python3
"""Scan every candidate, with only exact public-client declarations exempted."""
import subprocess
import sys
from pathlib import Path
from public_oauth import sanitize

root = Path(__file__).resolve().parent.parent
patterns = root / 'scripts/secret-pattern.txt'
try:
    if not patterns.read_bytes().strip():
        raise RuntimeError('empty patterns')
    listing = subprocess.run(
        ['rg', '--files-with-matches', '--pcre2', '--text', '--file', str(patterns),
         '--hidden', '--null', '--glob', '!/.git/**', '--glob', '!/.gradle/**',
         '--glob', '!**/build/**', '--glob', '!scripts/check-secrets.sh', str(root)],
        capture_output=True,
    )
    if listing.returncode not in (0, 1):
        raise RuntimeError("scanner failure")
    for item in listing.stdout.split(b'\0'):
        if not item:
            continue
        path = Path(item.decode())
        data = sanitize(path.relative_to(root).as_posix(), path.read_bytes())
        result = subprocess.run(
            ['rg', '--pcre2', '--text', '--quiet', '--file', str(patterns), '-'],
            input=data, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        if result.returncode != 1:
            raise RuntimeError('potential secret or scanner failure; content suppressed')
    print('Secret scan passed.')
except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError):
    print('check-secrets: potential secret or scanner failure; content suppressed', file=sys.stderr)
    sys.exit(1)

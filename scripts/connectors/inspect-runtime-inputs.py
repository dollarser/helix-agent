#!/usr/bin/env python3
"""Inspect supplied connector material without executing it or printing config/credential values."""
import argparse
import hashlib
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('directory', type=Path)
args = parser.parse_args()
root = args.directory.resolve(strict=True)
if not root.is_dir():
    parser.error('directory required')
manifest = hashlib.sha256()
hints = set()
elves = []
licenses = []
count = 0
for path in sorted(root.rglob('*')):
    if path.is_symlink() or not path.is_file():
        continue
    # Bound each read; this inventory is for exported metadata/skills, not large Runtime assets.
    if path.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('oversized material; inspect runtime assets separately')
    data = path.read_bytes()
    relative = path.relative_to(root).as_posix()
    manifest.update(relative.encode() + b'\0' + hashlib.sha256(data).digest())
    count += 1
    if data.startswith(b'\x7fELF'):
        elves.append({'path': relative, 'machine': int.from_bytes(data[18:20], 'little')})
    if path.name.upper().startswith(('LICENSE', 'COPYING')):
        licenses.append(relative)
    if path.suffix.lower() in {'.md', '.json', '.py', '.sh'}:
        for name in ('wecom', 'wecom-cli', 'wecomcli', 'dingtalk', 'dingtalk-cli', 'uvx', 'npx', 'node', 'python'):
            if re.search(rb'(?<![\w-])' + name.encode() + rb'(?![\w-])', data, re.I):
                hints.add(name)
print(json.dumps({'files': count, 'manifest_sha256': manifest.hexdigest(),
                  'command_mentions_not_execution_proof': sorted(hints),
                  'elf_assets': elves, 'license_files': licenses,
                  'status': 'INVENTORY_ONLY_NOT_RUNTIME_ACCEPTANCE'}, indent=2))

#!/usr/bin/env python3
"""Restore ignored build inputs from an existing checkout; never modify its files or lock."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
from urllib.parse import urlparse

source = Path(sys.argv[1])/'runtime/proot-app/src/main/assets/runtime'
destination = Path('runtime/proot-app/src/main/assets/runtime')
assert (source/'runtime-lock.json').read_bytes() == (destination/'runtime-lock.json').read_bytes()
lock=json.loads((destination/'runtime-lock.json').read_text())
rootfs=next(c for c in lock['components'] if c['id']=='alpine-rootfs')
name=Path(urlparse(rootfs['url']).path).name.removesuffix('.gz')
archive=source/'rootfs'/name
assert hashlib.sha256(archive.read_bytes()).hexdigest()==rootfs['sha256']
assert hashlib.sha256((source/'proot/loader').read_bytes()).digest()==hashlib.sha256(
    Path('runtime/proot-app/src/main/jniLibs/arm64-v8a/libhelix_loader.so').read_bytes()).digest()
inventory={}
for directory in ['rootfs','proot']:
    for path in (source/directory).rglob('*'):
        if not path.is_file(): continue
        relative=path.relative_to(source)
        target=destination/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        if target.exists(): assert target.read_bytes()==path.read_bytes(), 'Refusing different existing build input'
        else: shutil.copy2(path,target)
        inventory[str(relative)]=hashlib.sha256(target.read_bytes()).hexdigest()
Path('build/debug/2026-09-10/hxa183/restored-assets.json').write_text(json.dumps(inventory,indent=2))
print('Identical lock; RootFS digest verified; APK loader matches asset; copied inputs:',len(inventory))

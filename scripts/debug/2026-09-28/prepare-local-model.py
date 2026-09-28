#!/usr/bin/env python3
"""Download the pinned public evaluation asset, without credentials; verify before use."""
import hashlib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'build/hxa222-real-model'
NAME = 'Qwen3-0.6B-Q4_K_M.gguf'
REVISION = '6091bc857fe0dffa19c581a7ccc7def1b126ff54'
SHA = 'ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a'
SIZE = 396705472
OUT.mkdir(parents=True, exist_ok=True)
target = OUT / NAME
if not target.exists():
    partial = target.with_suffix('.part')
    url = f'https://modelscope.cn/models/unsloth/Qwen3-0.6B-GGUF/resolve/{REVISION}/{NAME}'
    subprocess.run(['curl', '--fail', '--location', '--retry', '2', '--max-time', '300', url, '-o', str(partial)], check=True)
    with partial.open('rb') as source:
        assert hashlib.file_digest(source, 'sha256').hexdigest() == SHA and partial.stat().st_size == SIZE
    partial.rename(target)
with target.open('rb') as source:
    assert hashlib.file_digest(source, 'sha256').hexdigest() == SHA and target.stat().st_size == SIZE
print(f'{NAME}: {SIZE} bytes, sha256={SHA}')

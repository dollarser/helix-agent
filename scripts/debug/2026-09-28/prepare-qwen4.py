"""Public pinned GGUF evaluation asset, never packaged or committed."""
from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[3]
target = root / 'build/hxa222-closeout/Qwen3-4B-Instruct-2507-Q4_K_M.gguf'
sha = '3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597'
if not target.exists():
    partial = target.with_suffix('.part')
    subprocess.run(['curl', '-fL', '--retry', '2', '--max-time', '600',
                    'https://modelscope.cn/models/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/0b0406b39725d752255ffeb48f102c66f98e14aa/Qwen3-4B-Instruct-2507-Q4_K_M.gguf',
                    '-o', str(partial)], check=True)
    with partial.open('rb') as content:
        assert hashlib.file_digest(content, 'sha256').hexdigest() == sha
    assert partial.stat().st_size == 2497281120
    partial.rename(target)
with target.open('rb') as content:
    assert hashlib.file_digest(content, 'sha256').hexdigest() == sha
print(sha, target.stat().st_size)

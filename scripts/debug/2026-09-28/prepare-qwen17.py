from pathlib import Path
import hashlib
import subprocess
root=Path(__file__).resolve().parents[3]
target=root/'build/hxa222-closeout/Qwen3-1.7B-Q4_K_M.gguf'
sha='b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897'
if not target.exists():
    part=target.with_suffix('.part')
    subprocess.run(['curl','-fL','--retry','2','--max-time','300','https://modelscope.cn/models/unsloth/Qwen3-1.7B-GGUF/resolve/1e3f49488e445e2148d2e065ea0612c320da65c3/Qwen3-1.7B-Q4_K_M.gguf','-o',str(part)],check=True)
    with part.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()==sha
    assert part.stat().st_size==1107409472
    part.rename(target)
with target.open('rb') as f: assert hashlib.file_digest(f,'sha256').hexdigest()==sha
print(sha, target.stat().st_size)

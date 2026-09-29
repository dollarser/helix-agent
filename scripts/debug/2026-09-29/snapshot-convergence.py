"""Copy a stable dirty source snapshot into this otherwise pristine checkout."""
from pathlib import Path
import subprocess, sys, hashlib, json, shutil
source=Path(sys.argv[1]).resolve()
target=Path.cwd()
out=target/'build/contract-convergence'

def git(*args):
    return subprocess.check_output(['git','-C',str(source),*args])

def snapshot():
    paths=set(git('diff','--name-only','HEAD','-z').decode().split('\0')) | set(git('ls-files','--others','--exclude-standard','-z').decode().split('\0'))
    return {p: hashlib.sha256((source/p).read_bytes()).hexdigest() if (source/p).is_file() else None for p in sorted(paths) if p}

before=snapshot()
patch=git('diff','--binary','HEAD')
(out/'inherited.patch').write_bytes(patch)
subprocess.run(['git','apply','--binary','-'],input=patch,check=True)
for rel in git('ls-files','--others','--exclude-standard','-z').decode().split('\0'):
    if rel:
        dest=target/rel
        dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(source/rel,dest)
if before != snapshot():
    raise RuntimeError('Source changed during capture; review snapshot before editing')
(out/'inherited-files.json').write_text(json.dumps(before,indent=2))
for rel in before:
    p=target/rel
    if p.is_file():
        backup=out/'inherited-files'/rel
        backup.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(p,backup)
if (source/'local.properties').exists():
    shutil.copy2(source/'local.properties',target/'local.properties')
print(f'Stably captured {len(before)} inherited paths; source unchanged')

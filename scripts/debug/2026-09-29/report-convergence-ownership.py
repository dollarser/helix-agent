"""Report only changes made after the inherited dirty-main snapshot."""
from pathlib import Path
import subprocess, json, difflib
root=Path.cwd()
out=root/'build/contract-convergence'
inherited=json.loads((out/'inherited-files.json').read_text())

def git(*args):
    return subprocess.check_output(['git',*args])

paths=set(git('diff','--name-only','HEAD','-z').decode().split('\0')) | set(git('ls-files','--others','--exclude-standard','-z').decode().split('\0'))
owned=[]
patch=[]
for path in sorted(paths - {''}):
    file=root/path
    current=file.read_bytes() if file.is_file() else None
    if path in inherited:
        base=out/'inherited-files'/path
        previous=base.read_bytes() if base.is_file() else None
    else:
        result=subprocess.run(['git','show','HEAD:'+path],capture_output=True)
        previous=result.stdout if result.returncode==0 else None
    if previous==current:
        continue
    owned.append(path)
    patch.extend(difflib.unified_diff(
        (previous or b'').decode().splitlines(keepends=True),
        (current or b'').decode().splitlines(keepends=True),
        fromfile='a/'+path if previous is not None else '/dev/null',
        tofile='b/'+path if current is not None else '/dev/null',
    ))
(out/'owned-paths.json').write_text(json.dumps(owned,indent=2))
(out/'owned.patch').write_text(''.join(patch))
print('\n'.join(owned))

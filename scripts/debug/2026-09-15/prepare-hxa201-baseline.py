"""Materialize the captured pre-task tree for matching regression attribution. No checkout mutation."""
import subprocess,tarfile,io,shutil,sys
from pathlib import Path
root=Path(__file__).resolve().parents[3]
name=sys.argv[1] if len(sys.argv)>1 else 'hxa201-baseline'
if not name.replace('-', '').isalnum():raise SystemExit('Expected a build directory name')
dest=root/'build'/name
if dest.exists():raise SystemExit('Refuse existing baseline')
dest.mkdir()
data=subprocess.check_output(['git','archive','aefc9789'],cwd=root)
with tarfile.open(fileobj=io.BytesIO(data)) as tar:tar.extractall(dest,filter='data')
# Run at the repository root with an explicit destination: subdirectory git apply
# otherwise silently skips repository-relative paths outside its invocation prefix.
subprocess.run(['git','apply','--directory',str(dest.relative_to(root)),str(root/'build/hxa201-closeout/initial.patch')],cwd=root,check=True)
for line in (root/'build/hxa201-closeout/initial-status.txt').read_text().splitlines():
 if not line.startswith('?? '):continue
 name=line[3:];source=root/name;target=dest/name
 if source.is_dir():shutil.copytree(source,target,dirs_exist_ok=True)
 elif source.exists():target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
if (root/'local.properties').exists():shutil.copy2(root/'local.properties',dest/'local.properties')
(dest/'build').mkdir(exist_ok=True)
(dest/'build/provenance.txt').write_text('aefc9789 + captured pre-task tracked diff and untracked paths; current HXA-201 changes excluded.\n')
print(dest.relative_to(root))

"""Reproducible production-source inventory; line counts include comments, not complexity scores."""
import json
from pathlib import Path
import re
import subprocess
files=subprocess.check_output(['rg','--files','-g','*.kt','-g','*.java','-g','*.cpp','-g','*.c','-g','!**/build/**'],text=True).splitlines()
rows=[]
for name in files:
 p=Path(name)
 parts=p.parts
 if 'src' not in parts: continue
 source=parts[parts.index('src')+1]
 if source not in {'main','consumer','developer'}: continue
 text=p.read_text()
 lines=text.splitlines()
 declarations=[{'line':i,'text':l.strip()} for i,l in enumerate(lines,1) if re.match(r'^(?:internal |private |public |open |abstract |sealed |data |enum |value |inline )*(?:class|object|interface|fun)\b',l) or re.match(r'^    (?:private |internal |override |suspend |inline |tailrec |operator )*fun\b',l)]
 rows.append({'path':name,'lines':len(lines),'declarations':declarations,'flagged':any(x in text for x in ['"LargeClass"','"TooManyFunctions"','"LongMethod"'])})
rows.sort(key=lambda x:(-x['lines'],x['path']))
out=Path('build/debug/2026-09-10/large-class-audit');out.mkdir(parents=True,exist_ok=True)
(out/'inventory.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2))
print('PRODUCTION FILES',len(rows))
for r in rows:
 if r['lines']>=400:
  print(r['lines'],r['path'])
print('UNDER400 FLAGS')
for r in rows:
 if r['lines']<400 and r['flagged']:
  print(r['lines'],r['path'])

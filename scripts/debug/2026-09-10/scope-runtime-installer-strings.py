# Historical one-shot implementation script from 2026-09-10.
# Preserved for provenance; do not rerun against current source.
from pathlib import Path
for locale in ['values','values-en','values-zh-rCN']:
 p=Path('app/src/main/res')/locale/'strings.xml';s=p.read_text();lines=s.splitlines(keepends=True)
 moved=[line for line in lines if '<string name="bundled_' in line]
 assert len(moved)==8
 p.write_text(''.join(line for line in lines if line not in moved))
 target=Path('app/src/developer/res')/locale/'runtime_installer_strings.xml'
 target.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'+''.join(moved)+'</resources>\n')

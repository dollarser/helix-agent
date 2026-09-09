#!/usr/bin/env python3
"""One-shot HXA-184 file organization; retains declaration text and same-package symbols."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[3]
changed=[]
def write(p,s):
    p.write_text(s); changed.append(str(p.relative_to(ROOT)))
def header(s):
    lines=s.splitlines(True); end=max(i for i,l in enumerate(lines) if l.startswith('import '))+1
    return ''.join(lines[:end])+'\n', ''.join(lines[end:]).lstrip()
def clean(h,b):
    return re.sub(r'^import .*\n',lambda m:m[0] if (m[0].strip().split('.')[-1] in ['getValue','setValue'] or re.search(r'\b'+re.escape(m[0].strip().split('.')[-1])+r'\b',b)) else '',h,flags=re.M)+b.rstrip()+'\n'
def move_blocks(rel,names,rename_helpers=False):
    p=ROOT/rel; h,b=header(p.read_text())
    if rename_helpers:
        symbols=re.findall(r'^private (?:fun|class) (\w+)',b,re.M)
        for n in symbols:
            replacement=p.stem[0].lower()+p.stem[1:]+n[0].upper()+n[1:]
            b=re.sub(r'\b'+n+r'\b',replacement,b)
        b=re.sub(r'^private (fun|class|val|const val)',r'internal \1',b,flags=re.M)
    for n in names:
        m=re.search(r'^(?:(?:internal|private) )?(?:class|object|interface) '+n+r'\b',b,re.M)
        assert m,n
        start=m.start()
        # Attach immediately preceding annotations and KDoc without consuming another declaration.
        prefix=b[:start].rstrip('\n')
        while prefix.endswith('*/') or prefix.split('\n')[-1].startswith('@'):
            if prefix.endswith('*/'): start=prefix.rfind('/**')
            else: start=prefix.rfind('\n')+1
            assert start>=0,n
            prefix=b[:start].rstrip('\n')
        end=b.index('\n}',m.end())+2
        block=b[start:end].replace('private class '+n,'internal class '+n)
        target=p.with_name(n+'.kt'); assert target==p or not target.exists(),target
        if target==p: continue
        write(target,clean(h,block)); b=b[:start]+b[end:]
    if b.strip(): write(p,clean(h,b.lstrip()))
    else: p.unlink(); changed.append(str(p.relative_to(ROOT)))
# Typed repositories stay unchanged; adjunct request/result types remain in the shared file.
for filename in ['ConversationRepositories','ConfigRepositories']:
    rel=f'core/storage/src/main/kotlin/com/helix/core/storage/repository/{filename}.kt'
    s=(ROOT/rel).read_text(); names=re.findall(r'^class (\w+)',s,re.M)
    move_blocks(rel,names)
rel='core/storage/src/main/kotlin/com/helix/core/storage/dao/ConversationDaos.kt'
move_blocks(rel,re.findall(r'^interface (\w+)',(ROOT/rel).read_text(),re.M))
for module,filename,names in [
('files','ArchiveTools',['FilesArchiveTool','FilesExtractTool']),
('files','FilesMetaTools',['FilesStatTool','FilesListTool','FilesSearchTool','FilesMkdirTool']),
('files','FilesMutateTools',['FilesCopyTool','FilesMoveTool','FilesDeleteTool']),
('android','NotificationsCalendarTools',['NotificationsQueryTool','CalendarPrepareEventTool','CalendarCommitEventTool']),
('android','AndroidSystemTools',['AndroidOpenUriTool','ClipboardReadTool','ClipboardWriteTool','AndroidShareTool']),
]: move_blocks(f'tools/{module}/src/main/kotlin/com/helix/tools/{module}/{filename}.kt',names,True)
move_blocks('tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationActions.kt',['AutomationFinder','AutomationNodeActionExecutor','AutomationWaiter'])
move_blocks('tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationSnapshotEngine.kt',['AndroidSnapshotNode','AccessibilityGenerationTracker'])
# Keep database companion API stable; migration SQL bodies move intact to a dedicated object.
p=ROOT/'core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt'; s=p.read_text()
a=s.index('        val MIGRATION_11_12'); z=s.rindex('    }')
body=s[a:z]; names=re.findall(r'^        val (MIGRATION_\d+_\d+)',body,re.M)
t=p.with_name('HelixMigrations.kt'); assert not t.exists()
write(t,'package com.helix.core.storage\n\nimport androidx.room.migration.Migration\nimport androidx.sqlite.db.SupportSQLiteDatabase\n\ninternal object HelixMigrations {\n'+''.join(l[4:] if l.startswith('    ') else l for l in body.splitlines(True))+'}\n')
write(p,s[:a]+''.join(f'        val {n} = HelixMigrations.{n}\n\n' for n in names)+s[z:])
# Durable manifest for subsequent formatting / verification and completion evidence.
manifest=ROOT/'build/debug/2026-09-10/hxa184'; manifest.mkdir(parents=True,exist_ok=True)
(manifest/'organized-paths.txt').write_text('\n'.join(changed)+'\n')
print(f'Organized {len(changed)} paths')

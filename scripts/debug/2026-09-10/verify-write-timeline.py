"""Scoped host regressions and app build; no emulator use."""
from pathlib import Path
import subprocess
out=Path('build/debug/2026-09-10/write-timeline');out.mkdir(parents=True,exist_ok=True)
paths=['tools/files/src/main/kotlin/com/helix/tools/files/WriteTool.kt','tools/files/src/test/kotlin/com/helix/tools/files/WriteToolTest.kt'] + ['app/src/main/kotlin/com/helix/app/ui/'+n+'.kt' for n in ['ToolPurpose','ToolTimelineItem','ConversationMessage']] + ['app/src/test/kotlin/com/helix/app/ui/ToolPurposeTest.kt']
commands=[['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(str(Path(p).resolve()) for p in paths)],['./gradlew',':tools:files:test','--tests','*WriteToolTest',':app:testDeveloperDebugUnitTest','--tests','*ToolPurposeTest',':app:assembleDeveloperDebug']]
for i,cmd in enumerate(commands):
    with (out/f'{i}.log').open('w') as log:r=subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT)
    print(i,r.returncode,flush=True)
    if r.returncode:raise SystemExit(r.returncode)

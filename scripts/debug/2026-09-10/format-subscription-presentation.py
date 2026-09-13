"""One-time presentation responsibility extraction and scoped formatting of our subscription changes."""
from pathlib import Path
import os, re, subprocess
path=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionScreen.kt')
s=path.read_text();start=s.index('        for (index in 0 until content.childCount)');end=s.index('        shell.addView(',start)
body=s[start:end]
s=s[:start]+'        styleContent(content, ::dp)\n'+s[end:]
pos=s.index('    private fun shape(')
s=s[:pos]+'    private fun styleContent(content: LinearLayout, dp: (Int) -> Int) {\n'+body+'    }\n\n'+s[pos:];path.write_text(s)
root=Path(__file__).resolve().parents[3]
paths=set(re.findall('('+re.escape(str(root))+r'/[^:\n]+\.kt):',Path('build/debug/2026-09-10/subscription-detekt.log').read_text()))
paths.add(str(path.resolve()))
with Path('build/debug/2026-09-10/subscription-scoped-format.log').open('w') as output:
 subprocess.run(['./gradlew','spotlessApply','-PspotlessIdeHook='+','.join(sorted(paths))],stdout=output,stderr=subprocess.STDOUT,check=True)

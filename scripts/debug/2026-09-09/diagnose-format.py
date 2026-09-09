"""Temporarily allow formatter output, then remove the diagnostic suppression before checks."""
from pathlib import Path
import sys
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/LongTurnCompactionDeviceTest.kt')
marker='@file:Suppress("ktlint:standard:max-line-length")\n\n'
s=p.read_text()
if sys.argv[1]=='prepare':
 assert not s.startswith(marker)
 p.write_text(marker+s)
else:
 assert s.startswith(marker)
 p.write_text(s[len(marker):])
 for n,line in enumerate(p.read_text().splitlines(),1):
  if len(line)>120: print(n,line)

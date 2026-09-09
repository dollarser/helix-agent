from pathlib import Path
import re
root=Path('app/src/main/kotlin/com/helix/app/chat')
for name in ['ChatService','ChatToolCalls','ChatToolSettlement','ChatToolTimeline','ChatRecoveryActions','ChatModelLoop','ChatGoalActions']:
 p=root/(name+'.kt'); s=p.read_text(); body=re.sub(r'^import .*\n','',s,flags=re.M)
 s=re.sub(r'^import ([\w.]+)(?: as (\w+))?\n',lambda m:m[0] if re.search(r'\b'+re.escape(m[2] or m[1].split('.')[-1])+r'\b',body) else '',s,flags=re.M)
 p.write_text(s)

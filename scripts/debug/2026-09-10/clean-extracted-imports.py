#!/usr/bin/env python3
"""Remove unused plain imports from the HXA-183 files, preserving delegated-property operators."""
from pathlib import Path
import re
ROOT=Path(__file__).resolve().parents[3]
groups={
'app/src/main/kotlin/com/helix/app/ui': ['ChatScreen','SessionListSection','ConversationSection','ConversationIntents','ConversationModeControls','ToolTimelineItem','ProviderScreen','ProviderFormDialog','ProviderRow','ProviderRowActions'],
'app/src/main/kotlin/com/helix/app/chat': ['ChatService','ChatToolCalls','ChatToolMessageEncoder','ChatDispatchRequests','ChatAttachmentRetry'],
'app/src/main/kotlin/com/helix/app/files': ['FileManagerServiceTransfers','FileManagerImports','FileManagerExports'],
'app/src/developer/kotlin/com/helix/app/proot': ['LinuxRunTool','LinuxJobExecution','LinuxInputSnapshot'],
'runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app': ['ProotJobRunner','ProotOutputArchive','ProotOutputCapture'],
}
for directory, names in groups.items():
    for name in names:
        p=ROOT/directory/(name+'.kt'); s=p.read_text()
        body=re.sub(r'^import .*\n','',s,flags=re.M)
        def keep(m):
            symbol=m.group().strip().split('.')[-1]
            return m.group() if symbol in ['getValue','setValue'] or re.search(r'\b'+re.escape(symbol)+r'\b',body) else ''
        p.write_text(re.sub(r'^import .*\n',keep,s,flags=re.M))
p=ROOT/'app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt'
s=p.read_text().replace("/** The conversation's intents, bundled so the composable stays within the parameter budget. */",'/** Renders one conversation from observable state and explicit UI intents. */')
p.write_text(s)

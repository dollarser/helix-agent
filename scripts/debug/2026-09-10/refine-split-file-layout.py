#!/usr/bin/env python3
"""HXA-183 declaration layout and precise unchanged request contract annotation."""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
ui=ROOT/'app/src/main/kotlin/com/helix/app/ui'
for source, target, marker in [
    ('ConversationSection.kt','ConversationIntents.kt','data class ConversationIntents('),
    ('ProviderRow.kt','ProviderRowActions.kt','internal data class ProviderRowActions('),
]:
    p=ui/source; s=p.read_text(); start=s.index(marker); end=s.index('\n)\n',start)+3
    header=s[:s.index('\n\n',s.index('import '))]+'\n\n'
    (ui/target).write_text(header+s[start:end])
    p.write_text(s[:start]+s[end:])
p=ROOT/'app/src/main/kotlin/com/helix/app/chat/ChatDispatchRequests.kt'
s=p.read_text().replace('@Suppress("LongMethod", "CyclomaticComplexMethod")','@Suppress("LongMethod", "CyclomaticComplexMethod", "LongParameterList")')
p.write_text(s)

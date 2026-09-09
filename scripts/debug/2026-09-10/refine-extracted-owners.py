#!/usr/bin/env python3
"""HXA-183 one-shot responsibility extraction, preserving shared owner references."""
from pathlib import Path
import textwrap
ROOT=Path(__file__).resolve().parents[3]
ui=ROOT/'app/src/main/kotlin/com/helix/app/ui'
for src,dst,marker in [
    ('SessionListSection.kt','ConversationSection.kt','/** The conversation'),
    ('ConversationModeControls.kt','ToolTimelineItem.kt','/**\n * One tool-timeline'),
]:
    p=ui/src; s=p.read_text()
    if marker not in s: continue
    i=s.index(marker); comment=s[i:]; p.write_text(s[:i])
    p=ui/dst; s=p.read_text(); i=s.index('\n\n',s.index('import ')); p.write_text(s[:i+2]+comment+'\n'+s[i+2:])

p=ROOT/'app/src/main/kotlin/com/helix/app/chat/ChatToolCalls.kt'; s=p.read_text()
start=s.rfind('    /**',0,s.index('    private fun buildDispatchRequest(')); end=s.index('    /**\n     * The durable settlement',start)
body=s[start:end].replace('private fun buildDispatchRequest(', 'fun build(')
header=s[:s.index('/** Owns')]
(p.parent/'ChatDispatchRequests.kt').write_text(header+'''/** Builds a trusted request from live facts; cancellation and dispatch state stay with their owners. */
internal class ChatDispatchRequests(
    private val toolPipeline: ToolPipeline,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope>,
) {
'''+body+'}\n')
s=s[:start]+s[end:]; s=s.replace('buildDispatchRequest(', 'requests.build(')
s=s.replace('    private val timeline =', '    private val requests = ChatDispatchRequests(toolPipeline, turnCancels, goalTimes, lanScopes)\n    private val timeline =')
p.write_text(s)

# Bounded snapshot building is separate from submit/query/result ownership.
p=ROOT/'app/src/developer/kotlin/com/helix/app/proot/LinuxJobExecution.kt'; s=p.read_text()
start=s.index('    /**\n     * Builds the bounded input zip'); end=s.index('    /**\n     * Imports the verified',start)
body=s[start:end].replace('private fun buildInputZip(', 'fun build(')
header=s[:s.index('/**')]
(p.parent/'LinuxInputSnapshot.kt').write_text(header+'''/** Builds the bounded input archive through the scoped store; never submits a job. */
internal class LinuxInputSnapshot(private val store: WorkspaceArtifactStore) {
'''+body+'}\n')
s=s[:start]+s[end:]; s=s.replace('buildInputZip(call.inputReferences, inputZip)', 'inputSnapshot.build(call.inputReferences, inputZip)')
s=s.replace(') : LinuxExecutor {', ') : LinuxExecutor {\n    private val inputSnapshot = LinuxInputSnapshot(store)')
p.write_text(s)

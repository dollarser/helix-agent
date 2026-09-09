from pathlib import Path
root=Path('app/src/main/kotlin/com/helix/app/chat');p=root/'ChatToolCalls.kt';s=p.read_text();imports=s[:s.index('/**')]
a=s.index('    /**\n     * Publishes (or replaces)');b=s.index('    private fun boundedSummary',a)
timeline=s[a:b]
for name in ['publishToolRow','attachCardToRow','updateCard','setCardStateForCall']: timeline=timeline.replace('private fun '+name,'fun '+name)
(root/'ChatToolTimeline.kt').write_text(imports+'''/** Atomic updates scoped to tool rows; does not own session admission or network jobs. */
@Suppress("LongParameterList")
internal class ChatToolTimeline(private val _screen: MutableStateFlow<ChatScreenState>,
    private val strings: (Int, Array<out Any>) -> String) {
    private fun str(resId: Int, vararg args: Any): String = strings(resId, args)
    fun hasCall(callId: String): Boolean = _screen.value.toolTimeline.any { it.callId == callId }
'''+timeline+'}\n')
s=s[:a]+s[b:]
a=s.index('    private fun settleToolCall(');b=s.index('    private fun boundedSummary',a)
settlement=s[a:b];s=s[:a]+s[b:]
a=s.index('    private fun boundedSummary');b=s.index('    private companion',a)
summary=s[a:b];s=s[:a]+s[b:]
settlement=settlement.replace('private fun settleToolCall','fun settleToolCall').replace('private fun persistRejectedToolCall','fun persistRejectedToolCall').replace('dispatchFacts[toolCallId]?.descriptor?.origin','originFor(toolCallId)')
for name in ['publishToolRow','setCardStateForCall']: settlement=settlement.replace(name+'(', 'timeline.'+name+'(')
(root/'ChatToolSettlement.kt').write_text(imports+'''/** Persists each scheduled outcome and projects its verified or uncertain state without replay. */
@Suppress("LongParameterList")
internal class ChatToolSettlement(
    private val storage: HelixStorage, private val toolPipeline: ToolPipeline,
    private val clock: Clock, private val idGenerator: () -> String,
    private val strings: (Int, Array<out Any>) -> String,
    private val timeline: ChatToolTimeline,
    private val originFor: (String) -> com.helix.tools.framework.ToolOrigin?,
) {
    private fun str(resId: Int, vararg args: Any): String = strings(resId, args)
'''+settlement+summary+'    private companion object { const val SUMMARY_CAP = 500 }\n}\n')
for name in ['publishToolRow','attachCardToRow','updateCard','setCardStateForCall']: s=s.replace(name+'(', 'timeline.'+name+'(')
for name in ['settleToolCall','persistRejectedToolCall']: s=s.replace(name+'(', 'settlement.'+name+'(')
s=s.replace('        val existing = _screen.value.toolTimeline.firstOrNull { it.callId == callId }\n        if (existing != null) {','        if (timeline.hasCall(callId)) {')
s=s.replace('    private val _screen: MutableStateFlow<ChatScreenState>,','    screen: MutableStateFlow<ChatScreenState>,')
pos=s.index('    private val dispatchFacts')
s=s[:pos]+'''    private val timeline = ChatToolTimeline(screen, strings)
    private val settlement = ChatToolSettlement(storage, toolPipeline, clock, idGenerator, strings, timeline) {
        dispatchFacts[it]?.descriptor?.origin
    }
'''+s[pos:]
p.write_text(s)
p=root/'ChatService.kt';s=p.read_text().replace('    /** The trusted card facts per model call id, set before the dispatch, read by the card sink. */\n','').replace('    /** The approval card currently waiting for a user decision (for stop() to cancel). */\n','');p.write_text(s)

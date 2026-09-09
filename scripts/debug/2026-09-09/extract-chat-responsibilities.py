from pathlib import Path
root=Path('app/src/main/kotlin/com/helix/app/chat');p=root/'ChatService.kt';s=p.read_text();imports=s[:s.index('/**')]
# Preserve an exact pre-refactor snapshot outside the source set for review.
Path('build/phone-polish/hxa179-ChatService-before.kt.txt').write_text(s)
start=s.index('    private fun assistantToolStepJson(');end=s.index('    // --------------------------------------------------------------------------------\n    // Screen state',start)
tools=s[start:end];s=s[:start]+s[end:]
a=tools.index('    /** The turn\'s [CancelSignal]');b=tools.index('    /**\n     * One model call',a)
cancel=tools[a:b].replace('    private class TurnCancelSignal','internal class TurnCancelSignal');tools=tools[:a]+tools[b:]
(root/'TurnCancelSignal.kt').write_text('package com.helix.app.chat\n\nimport com.helix.tools.framework.CancelSignal\n\n'+cancel)
a=s.index('    /** The approval card\'s');b=s.index('    /**\n     * Retries the newest',a)
approval=s[a:b];s=s[:a]+'''    fun approveApproval(approvalId: String) = toolCalls.approveApproval(approvalId)
    fun denyApproval(approvalId: String) = toolCalls.denyApproval(approvalId)
    fun onApprovalCard(approvalId: String, request: ApprovalRequest) = toolCalls.onApprovalCard(approvalId, request)
    fun dispatchToolCall(
        toolCallId: String, turnId: String, toolNameRaw: String, rawArgsJson: String,
        mode: AgentMode = AgentMode.ACT, chatToolsEnabled: Boolean = false,
    ): ToolDispatchOutcome = toolCalls.dispatchToolCall(
        toolCallId, turnId, toolNameRaw, rawArgsJson, mode, chatToolsEnabled,
    )

'''+s[b:]
# Tool state is owned by the extracted component; cancellation/time windows stay with Turn orchestration.
s=s.replace('    private val dispatchFacts = java.util.concurrent.ConcurrentHashMap<String, DispatchFacts>()','')
s=s.replace('    @Volatile\n    private var activePendingApprovalId: String? = null','')
s=s.replace('activePendingApprovalId?.let { toolPipeline.broker.cancel(it) }','toolCalls.cancelPendingApproval()')
s=s.replace('        dispatchFacts.values.removeIf { it.turnId == turnId }\n        activePendingApprovalId = null','        toolCalls.finishTurn(turnId)')
for name in ['assistantToolStepJson','toolResultDraft','runToolBatch']:
 s=s.replace(name+'(', 'toolCalls.'+name+'(')
 tools=tools.replace('private fun '+name,'fun '+name)
tools=tools.replace('private data class SettledCall','data class SettledCall')
constructor='''/** Owns tool dispatch facts, approval decisions and ordered durable tool settlement. */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ChatToolCalls(
    private val storage: HelixStorage,
    private val toolPipeline: ToolPipeline,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val profile: StateFlow<SafetyProfile>,
    private val workScope: CoroutineScope,
    private val _screen: MutableStateFlow<ChatScreenState>,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val strings: (Int, Array<out Any>) -> String,
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope>,
) {
    private val dispatchFacts = java.util.concurrent.ConcurrentHashMap<String, DispatchFacts>()
    @Volatile private var activePendingApprovalId: String? = null
    private fun str(resId: Int, vararg args: Any): String = strings(resId, args)
    fun cancelPendingApproval() { activePendingApprovalId?.let { toolPipeline.broker.cancel(it) } }
    fun finishTurn(turnId: String) {
        dispatchFacts.values.removeIf { it.turnId == turnId }
        activePendingApprovalId = null
    }
'''
(root/'ChatToolCalls.kt').write_text(imports+constructor+approval+tools+'''    private companion object { const val TAG = "HelixChatTools"; const val SUMMARY_CAP = 500 }
}
''')
# Recovery controls have their own serialization and own only recovery-row overlays.
a=s.index('    private val subscriptionRecoveryMutex');b=s.index('    fun approveApproval(',a)
recovery=s[a:b].replace('screen.value','_screen.value')
s=s[:a]+'''    fun inspectInterruptedSubscription(turnId: String, modelCallId: String, stop: Boolean) =
        recovery.inspectInterruptedSubscription(turnId, modelCallId, stop)
    fun recoverInterruptedSubscriptionResult(turnId: String, modelCallId: String) =
        recovery.recoverInterruptedSubscriptionResult(turnId, modelCallId)
    fun inspectInterruptedProot(turnId: String, callId: String, stop: Boolean) =
        recovery.inspectInterruptedProot(turnId, callId, stop)
    fun recoverInterruptedProot(turnId: String, callId: String) = recovery.recoverInterruptedProot(turnId, callId)
    fun retryProotAcknowledgement(turnId: String, callId: String) = recovery.retryProotAcknowledgement(turnId, callId)

'''+s[b:]
(root/'ChatRecoveryActions.kt').write_text(imports+'''/** Serializes explicit recovery actions without restarting a Turn or replaying a job. */
internal class ChatRecoveryActions(
    private val storage: HelixStorage,
    private val workScope: CoroutineScope,
    private val _screen: MutableStateFlow<ChatScreenState>,
    private val subscriptionRecovery: (String, String, Boolean) -> com.helix.app.provider.SubscriptionRecoveryStatus,
    private val subscriptionResultRecovery: (String, String, Boolean) -> com.helix.app.provider.SubscriptionRecoveredOutput?,
) {
'''+recovery+'}\n')
# Lazy delegates are wired only after the facade's state fields have initialized.
pos=s.index('    private val workScope = scope')+len('    private val workScope = scope')
s=s[:pos]+'''
    private val toolCalls by lazy {
        ChatToolCalls(storage, toolPipeline, clock, idGenerator, profile, workScope, _screen,
            turnCancels, goalTimes, strings, lanScopes)
    }
    private val recovery by lazy {
        ChatRecoveryActions(storage, workScope, _screen, subscriptionRecovery, subscriptionResultRecovery)
    }
'''+s[pos:]
a=s.index(' * ChatService owns every');b=s.index(' */',a)
s=s[:a]+''' * This facade owns session admission and live Turn orchestration. Tool dispatch/approval
 * and recovery actions have explicit collaborators; UI state updates remain atomic.
'''+s[b:]
p.write_text(s)

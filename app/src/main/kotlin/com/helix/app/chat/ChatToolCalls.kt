package com.helix.app.chat

import android.util.Log
import com.helix.app.R
import com.helix.app.agent.BufferedModelToolCall
import com.helix.app.agent.GoalTimeBudget
import com.helix.app.agent.LocalToolCallBatch
import com.helix.app.agent.SettledCall
import com.helix.app.agent.TurnCancelSignal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnMessageDraft
import com.helix.app.agent.TurnToolExecutor
import com.helix.app.approval.ApprovalCancelledException
import com.helix.app.approval.ApprovalCardState
import com.helix.app.approval.ApprovalUiMapper
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.tool.ToolPipeline
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.Clock
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.policy.DataOrigin
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.TurnEntity
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.DispatchOutcomeCode
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchOutcome
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.Volatile

/**
 * Owns tool dispatch facts, approval decisions and ordered durable tool settlement. Also the
 * agent loop's [TurnToolExecutor] port (HX2-02): the loop's tool rounds execute through here.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ChatToolCalls(
    private val storage: HelixStorage,
    private val toolPipeline: ToolPipeline,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val profile: StateFlow<SafetyProfile>,
    private val workScope: CoroutineScope,
    screen: MutableStateFlow<ChatScreenState>,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val strings: (Int, Array<out Any>) -> String,
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope>,
) : TurnToolExecutor {
    private val requests = ChatDispatchRequests(toolPipeline, turnCancels, goalTimes, lanScopes)
    private val timeline = ChatToolTimeline(screen, strings)
    private val outcomeStore =
        ChatToolSettlement(storage, toolPipeline, clock, idGenerator, strings, timeline) {
            dispatchFacts[it]?.descriptor?.origin
        }
    private val dispatchFacts = java.util.concurrent.ConcurrentHashMap<String, DispatchFacts>()

    @Volatile private var activePendingApprovalId: String? = null

    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    fun cancelPendingApproval() {
        activePendingApprovalId?.let { toolPipeline.broker.cancel(it) }
    }

    fun finishTurn(turnId: String) {
        dispatchFacts.values.removeIf { it.turnId == turnId }
        activePendingApprovalId = null
    }

    /** The approval card's "本次批准" action (UI -> service -> broker, on the work scope). */
    fun approveApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.APPROVED)
    }

    /** The approval card's "拒绝" action (UI -> service -> broker, on the work scope). */
    fun denyApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.DENIED)
    }

    // The tap path must survive ANY failure on the stale-card / unknown-record path
    // (the repository's one-time guard throws, but a broad catch guarantees the user's
    // tap is always visibly handled — never a crash, never a silent no-op).
    @Suppress("TooGenericExceptionCaught")
    private fun decideApproval(
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        workScope.launch {
            try {
                toolPipeline.broker.decide(approvalId, decision)
                if (decision == ApprovalDecision.APPROVED) {
                    timeline.updateCard(approvalId) { it.copy(state = ApprovalCardState.APPROVED) }
                } else {
                    timeline.updateCard(approvalId) {
                        it.copy(
                            state = ApprovalCardState.DENIED,
                            terminalDetail = str(R.string.approval_terminal_user_denied),
                        )
                    }
                }
            } catch (e: Exception) {
                // A stale card (the record was already decided or the id is unknown): the
                // repository's one-time guard throws — surface a stable card error, never a
                // crash, never a silent no-op (the user's tap must be visible as handled).
                Log.e(TAG, "approval $approvalId could not be decided", e)
                timeline.updateCard(approvalId) {
                    it.copy(
                        state = ApprovalCardState.FAILED,
                        terminalDetail = str(R.string.approval_terminal_op_failed),
                    )
                }
            }
        }
    }

    /**
     * The broker's card sink (installed by the container): publishes the pending card into
     * the timeline for the model call that requested it. The card is built from the TRUSTED
     * facts captured at request time (descriptor, profile-at-request-time, the canonical
     * arguments) — the display can never drift from what the binding hashes. Fails closed
     * (error) when the facts are missing: a card that cannot be rendered truthfully cannot
     * be approved.
     */
    fun onApprovalCard(
        approvalId: String,
        request: ApprovalRequest,
    ) {
        val callId = request.binding.toolCallId
        val facts = dispatchFacts[callId]
        val descriptor = facts?.descriptor
        if (facts == null || descriptor == null) {
            error("approval card requested without dispatch facts for model call $callId")
        }
        val card =
            ApprovalUiMapper.buildCard(
                approvalId = approvalId,
                binding = request.binding,
                state = ApprovalCardState.PENDING,
                descriptor = descriptor,
                arguments = facts.args,
                dynamicRisk = request.dynamicRisk,
                profile = facts.profile,
                dataOrigin = facts.dataOrigin,
                egressOrigin = facts.egress?.endpoint?.origin,
                egressResidence =
                    facts.egress
                        ?.endpoint
                        ?.residence()
                        ?.name,
                egressCategory = facts.egress?.dataSensitivity,
                boundedRule = ApprovalUiMapper.boundedRuleUi(request.boundedEgressRule),
                confirmationDetail = request.confirmationDetail,
                terminalDetail = null,
            )
        activePendingApprovalId = approvalId
        storage.toolCalls
            .byTurnAndCallId(facts.turnId, callId)
            ?.let { row -> storage.toolCalls.updateState(row, ToolCallState.AWAITING_APPROVAL) }
        // The stream path publishes a request row before the dispatch, so the card
        // attaches to it; a direct-dispatch caller (tests, future non-stream flows) has
        // no row yet — create one from the trusted facts. A card is NEVER dropped:
        // an approval that cannot be shown cannot be approved.
        if (timeline.hasCall(callId)) {
            timeline.attachCardToRow(callId, card)
        } else {
            timeline.publishToolRow(
                turnId = facts.turnId,
                callId = callId,
                toolName = request.binding.toolName,
                requestSummary = CanonicalArgs.canonicalize(facts.args),
                stateLabel = str(R.string.tool_state_awaiting_approval),
                resultSummary = null,
                card = card,
            )
        }
    }

    private val messageEncoder = ChatToolMessageEncoder(strings)

    override fun assistantToolStepJson(batch: LocalToolCallBatch): String = messageEncoder.assistantToolStepJson(batch)

    override fun toolResultDraft(settled: SettledCall): TurnMessageDraft = messageEncoder.toolResultDraft(settled)

    // --------------------------------------------------------------------------------
    // HXA-036: tool call processing (model tool calls -> dispatcher -> timeline)
    // --------------------------------------------------------------------------------

    /**
     * The trusted facts the approval card is built from, captured at REQUEST time (before
     * the dispatch). The card must show what the binding hashes — the profile at request
     * time, the descriptor, the canonical arguments — so these facts are immutable once
     * captured; a later profile switch cannot change a pending card (roadmap HXA-036 test
     * B1: 切换 Profile 不改变待审批决定).
     */
    private data class DispatchFacts(
        val descriptor: ToolDescriptor?,
        val args: JsonObject,
        val profile: SafetyProfile,
        val dataOrigin: DataOrigin,
        val turnId: String,
        /** The call's egress facet (null when the call does not egress) — the card shows
         * origin / residence / data category from these trusted facts. */
        val egress: com.helix.core.policy.EgressRequest? = null,
    )

    /**
     * One model call's tool round (roadmap HXA-037; doc 11 section 3): prepares every
     * finished tool call (persist the tool_call row with the CANONICAL argument bytes —
     * doc 02 section 9.1/9.2: the stored argsJson is the same text the approval binding
     * hashes; the row's primary key is an app-generated local id, the approvals table's foreign
     * key targets tool_calls.id), then runs them through the [ToolScheduler] — bounded
     * platform-decided parallelism, call-order deterministic settlement.
     *
     * Every call gets a DURABLE outcome (doc 11 section 7): a dispatcher abort (turn
     * stop during an approval wait) settles the affected call CANCELLED and rethrows
     * [ApprovalCancelledException] AFTER all settled calls are persisted.
     *
     * This runs on the work scope's IO thread — the scheduler and the broker's blocking
     * user-decision wait never touch the main thread.
     */
    override fun runToolBatch(
        turn: com.helix.core.storage.entity.TurnEntity,
        turnId: String,
        calls: List<BufferedModelToolCall>,
        coordinator: TurnCoordinator,
        control: RunControlConfig,
    ): List<SettledCall> {
        val prepareds =
            calls.map { call ->
                prepareToolCall(turn, call.callId, call.name, call.arguments, control.mode, control.chatToolsEnabled)
            }
        val requests = prepareds.mapNotNull { it.request }
        val batch =
            if (requests.isEmpty()) {
                ToolScheduler.BatchResult(emptyList())
            } else {
                toolPipeline.scheduler.scheduleBatch(requests)
            }
        var slot = 0
        val settled =
            prepareds.map { p ->
                if (p.preSettled != null) {
                    // Malformed BEFORE the dispatcher (invalid name / non-object args):
                    // persistRejectedToolCall already wrote row + result + audit.
                    coordinator.settleBatchCall(p.callId, sideEffectUnknown = false)
                    SettledCall(p.callId, p.toolNameRaw, p.preSettled)
                } else {
                    val settlement = batch.settlements[slot++]
                    val thrown = (settlement as? ToolScheduler.BatchSettlement.Thrown)?.cause
                    val unknown =
                        (thrown != null && thrown !is ApprovalCancelledException) ||
                            (
                                (settlement as? ToolScheduler.BatchSettlement.Outcome)?.outcome
                                    as? ToolDispatchOutcome.ExecutionFailed
                            )?.requiresReview == true
                    val outcome =
                        when (settlement) {
                            is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                            is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
                        }
                    outcomeStore.settleToolCall(p.row!!, p.callId, p.toolNameRaw, outcome, unknown)
                    coordinator.settleBatchCall(p.callId, sideEffectUnknown = unknown)
                    SettledCall(p.callId, p.toolNameRaw, outcome)
                }
            }
        batch.firstError?.let { error ->
            if (error is ApprovalCancelledException) {
                // The turn is over (doc 11: cancel leaves a durable outcome for every
                // queued call — all slots above are settled); drop the signal and
                // propagate the turn-level cancellation.
                turnCancels.remove(turnId)
            }
            throw error
        }
        return settled
    }

    /** One prepared tool call: the persisted row + dispatch request, or a pre-settled rejection. */
    private class PreparedToolCall(
        val callId: String,
        val toolNameRaw: String,
        val row: com.helix.core.storage.entity.ToolCallEntity?,
        val request: ToolDispatchRequest?,
        val preSettled: ToolDispatchOutcome.Denied?,
    )

    /**
     * The per-call preparation of the tool pipeline (roadmap HXA-036/037; doc 11: the
     * Dispatcher is the only path between model-requested calls and implementations):
     * validate the name/arguments, persist the tool_call row with the canonical bytes,
     * publish the timeline row, build the trusted dispatch request and register the card
     * facts. Malformed input the dispatcher can never see (an invalid tool name,
     * non-object arguments) is persisted + audited HERE as a stable
     * [ToolDispatchOutcome.Denied] (preSettled) — the dispatcher is never fed garbage.
     */
    private fun prepareToolCall(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
    ): PreparedToolCall =
        if (GoalToolCallBudget(storage, clock).reserve(turn.id, toolCallId)) {
            prepareAdmittedToolCall(turn, toolCallId, toolNameRaw, rawArgsJson, mode, chatToolsEnabled)
        } else {
            PreparedToolCall(
                toolCallId,
                toolNameRaw,
                null,
                null,
                outcomeStore.persistRejectedToolCall(
                    turn,
                    toolCallId,
                    toolNameRaw,
                    rawArgsJson,
                    "unknown",
                    DispatchOutcomeCode.BUDGET_EXHAUSTED,
                    str(R.string.model_error_goal_budget_limit),
                ),
            )
        }

    private fun prepareAdmittedToolCall(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
    ): PreparedToolCall {
        val turnId = turn.id
        val toolName: ToolName? = runCatching { ToolName(toolNameRaw) }.getOrNull()
        val descriptor = toolPipeline.resolveLatest(toolNameRaw)
        // No-argument tools (e.g. time.now, whose ONLY valid input is {}) receive
        // arguments as an empty string or no argument fragments at all on many
        // OpenAI-compatible servers (observed: Ollama) — the decoders skip blank
        // fragments, so the accumulated buffer ends up empty. Normalize empty to the
        // empty object: a tool that REQUIRES arguments still gets its precise schema
        // rejection (missing properties), instead of the misleading "not a valid JSON
        // object" for a call the model made correctly.
        val normalizedArgs = if (rawArgsJson.isBlank()) "{}" else rawArgsJson
        val args: JsonObject? = parseJsonObjectOrNull(normalizedArgs)
        // Malformed input the dispatcher can never see (an invalid tool name, non-object
        // arguments) is persisted + audited HERE as a stable Denied (preSettled).
        val rejection = invalidToolCallRejection(turn, toolCallId, toolNameRaw, rawArgsJson, toolName, args, descriptor)
        rejection?.let { return it }
        val validName = toolName!!
        val validArgs = args!!
        val canonical = CanonicalArgs.canonicalize(validArgs)
        val row =
            storage.toolCalls.append(
                id = toolCallId,
                turnId = turnId,
                callId = toolCallId,
                name = toolNameRaw,
                version = descriptor?.version?.value?.toString() ?: "0",
                argsJson = canonical,
                state = ToolCallState.PENDING.name,
            )
        // The card facts: profile at REQUEST time (the consumer profile is STANDARD-pinned;
        // a later switch must not change a pending card — the card renders these trusted
        // facts, never the live store).
        val profile = profile.value
        timeline.publishToolRow(
            turnId,
            toolCallId,
            toolNameRaw,
            canonical,
            str(R.string.tool_state_processing),
            null,
            null,
        )
        val request =
            requests
                .build(
                    turn,
                    toolCallId,
                    validName,
                    descriptor,
                    validArgs,
                    profile,
                    mode,
                    chatToolsEnabled,
                ).copy(onExecutionStarting = {
                    storage.toolCalls.updateState(row, ToolCallState.RUNNING)
                    timeline.publishToolRow(
                        turnId,
                        toolCallId,
                        toolNameRaw,
                        canonical,
                        str(R.string.tool_state_running),
                        null,
                        null,
                    )
                })
        dispatchFacts[toolCallId] =
            DispatchFacts(descriptor, validArgs, profile, DataOrigin.WORKSPACE, turnId, request.egress)
        return PreparedToolCall(toolCallId, toolNameRaw, row, request, null)
    }

    /** The pre-settled Denied for an invalid tool NAME or non-object ARGUMENTS; null when both are valid. */
    @Suppress("LongParameterList") // one parameter per validated fact; splitting the pair would obscure the invariant
    private fun invalidToolCallRejection(
        turn: com.helix.core.storage.entity.TurnEntity,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        toolName: ToolName?,
        args: JsonObject?,
        descriptor: ToolDescriptor?,
    ): PreparedToolCall? =
        when {
            toolName == null -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    outcomeStore.persistRejectedToolCall(
                        turn,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        "unknown",
                        DispatchOutcomeCode.UNKNOWN_TOOL,
                        str(R.string.tool_rejected_bad_name),
                    ),
                )
            }

            args == null -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    outcomeStore.persistRejectedToolCall(
                        turn,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        descriptor?.version?.value?.toString() ?: "unknown",
                        DispatchOutcomeCode.INVALID_ARGUMENTS,
                        str(R.string.tool_rejected_bad_args),
                    ),
                )
            }

            else -> {
                null
            }
        }

    /**
     * The single per-call entry point of the tool pipeline (roadmap HXA-036; kept for the
     * direct (non-stream) callers and the device tests): prepare → single-call scheduler
     * batch → settle. The dispatcher MAY BLOCK on the approval card's user-decision wait,
     * so never call this from the main thread. The turn row must already be persisted;
     * the session id is the turn's PERSISTED session (a trusted fact). The mode is
     * [AgentMode.ACT]: the chat UI has no Plan/Goal tool surface yet (those come with
     * their own milestones) — when one arrives it feeds the request's mode field, and the
     * Policy Engine's Plan gate (READ_ONLY + L1 ceiling) applies from that request on.
     *
     * A turn stop during the approval wait settles the call as CANCELLED (doc 11: every
     * queued call gets a durable outcome) and rethrows [ApprovalCancelledException] for
     * the turn-level handler.
     */
    fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode = AgentMode.ACT,
        chatToolsEnabled: Boolean = false,
    ): ToolDispatchOutcome {
        val turn = storage.turns.resolve(turnId)
        val prepared = prepareToolCall(turn, toolCallId, toolNameRaw, rawArgsJson, mode, chatToolsEnabled)
        prepared.preSettled?.let { return it }
        val batch = toolPipeline.scheduler.scheduleBatch(listOf(prepared.request!!))
        val settlement = batch.settlements.single()
        val thrown = (settlement as? ToolScheduler.BatchSettlement.Thrown)?.cause
        val unknown = thrown != null && thrown !is ApprovalCancelledException
        val outcome =
            when (settlement) {
                is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
            }
        outcomeStore.settleToolCall(prepared.row!!, toolCallId, toolNameRaw, outcome, unknown)
        // The call has settled (either way): its cancel signal has served its purpose.
        // Releasing it here (the direct path has no turn-level finalizer, unlike the
        // stream path) prevents both a process-lifetime leak and a later stop() reaching
        // a call of this turn that was never started.
        turnCancels.remove(turnId)
        batch.firstError?.let { error ->
            throw error
        }
        return outcome
    }

    /** A JSON object, or null for any malformed input (parse failures are swallowed — the
     * rejection path handles the malformed input itself; the raw parse text is model
     * content and is never logged or shown). */
    private fun parseJsonObjectOrNull(raw: String): JsonObject? =
        runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject

    /**
     * The durable settlement for a slot the dispatcher threw away instead of returning
     * (the scheduler records it as that slot's [ToolScheduler.BatchSettlement.Thrown]).
     * The honest outcome depends on the cause: the broker's cancel exception is the ONLY proof that
     * nothing executed ("cancelled before start, no side effects" -> CANCELLED); every
     * other throw (executor crash, pool rejection, framework ISE) means the side-effect
     * state is UNKNOWN -> FAILED. Settling an unknown as "no side effects" would tell
     * the model the call never happened while the audit row says it failed — the exact
     * settlement/audit disagreement the audit page exists to prevent.
     */
    private fun unsettledSlotSettlement(error: Throwable?): ToolDispatchOutcome =
        if (error is ApprovalCancelledException) {
            ToolDispatchOutcome.Cancelled
        } else {
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                str(
                    R.string.tool_interrupted_by_orchestrator,
                    error?.javaClass?.simpleName ?: str(R.string.common_unknown),
                ),
            )
        }

    private companion object {
        const val TAG = "HelixChatTools"
        const val SUMMARY_CAP = 500
    }
}

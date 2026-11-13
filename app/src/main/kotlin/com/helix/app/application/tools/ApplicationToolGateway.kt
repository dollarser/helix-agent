package com.helix.app.application.tools

import android.util.Log
import com.helix.app.agent.UnresolvedEffectPolicy
import com.helix.app.tool.ToolPipeline
import com.helix.core.agent.AgentToolGateway
import com.helix.core.agent.BufferedModelToolCall
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SettledBatch
import com.helix.core.agent.SettledCall
import com.helix.core.agent.runtime.TurnExecutionHandles
import com.helix.core.model.AgentMode
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ApprovalWaitCancelledException
import com.helix.core.model.Clock
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.policy.DataOrigin
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.CanonicalArgs
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Owns tool dispatch facts, approval decisions and ordered durable tool settlement. Also the
 * agent loop's [AgentToolGateway] port (HX2-02): the loop's tool rounds execute through here.
 */
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
internal class ApplicationToolGateway(
    private val store: ToolGatewayStore,
    settlementStore: ToolSettlementStore,
    private val toolPipeline: ToolPipeline,
    private val clock: Clock,
    private val profile: StateFlow<SafetyProfile>,
    private val workScope: CoroutineScope,
    private val presentation: ToolPresentationSink,
    private val liveHandles: TurnExecutionHandles,
    private val lanScopes: () -> Set<com.helix.core.policy.NetworkOriginScope>,
    private val workspaceScopeId: String,
) : AgentToolGateway,
    ToolExecution {
    private val requests = ToolDispatchRequests(toolPipeline, liveHandles, lanScopes)
    private val outcomeStore =
        ToolSettlement(
            settlementStore,
            presentation,
        ) { dispatchFacts[it]?.descriptor?.origin }
    private val dispatchFacts = java.util.concurrent.ConcurrentHashMap<String, DispatchFacts>()
    private val executionStartTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val pendingApprovals = PendingTurnApprovals()

    fun reviewNativeApproval(
        sessionId: String,
        turnId: String,
        approvalId: String,
        approve: Boolean,
    ) {
        workScope.launch {
            val pending = pendingApprovals.find(approvalId)?.takeIf { it.turnId == turnId } ?: return@launch
            if (!store.canReviewApproval(sessionId, turnId, pending.callId)) return@launch
            if (pendingApprovals.claim(turnId, approvalId) == null) return@launch
            decideApproval(approvalId, if (approve) ApprovalDecision.APPROVED else ApprovalDecision.DENIED)
        }
    }

    fun cancelPendingApproval(turnId: String) {
        emit(ToolPresentationFact.TurnFinished(turnId))
        pendingApprovals.forTurn(turnId).forEach { toolPipeline.broker.cancel(it) }
    }

    fun finishTurn(turnId: String) {
        emit(ToolPresentationFact.TurnFinished(turnId))
        val callsForTurn = dispatchFacts.filterValues { it.turnId == turnId }.keys
        callsForTurn.forEach { executionStartTimes.remove(it) }
        dispatchFacts.values.removeIf { it.turnId == turnId }
        pendingApprovals.finishTurn(turnId)
    }

    /** The approval card's "本次批准" action (UI -> service -> broker, on the work scope). */
    fun approveApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.APPROVED)
    }

    /** The approval card's "拒绝" action (UI -> service -> broker, on the work scope). */
    fun denyApproval(approvalId: String) {
        decideApproval(approvalId, ApprovalDecision.DENIED)
    }

    /** Durable broker decisions do not depend on disposable card publication success. */
    @Suppress("TooGenericExceptionCaught")
    private fun decideApproval(
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        workScope.launch {
            try {
                toolPipeline.broker.decide(approvalId, decision)
            } catch (failure: Exception) {
                pendingApprovals.remove(approvalId)
                Log.e(TAG, "approval $approvalId could not be decided", failure)
                emit(ToolPresentationFact.ApprovalDecisionFailed(approvalId))
                return@launch
            }
            pendingApprovals.remove(approvalId)
            emit(ToolPresentationFact.ApprovalDecided(approvalId, decision))
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
    override fun onApprovalCard(
        approvalId: String,
        request: ApprovalRequest,
    ): Pair<ToolDescriptor, com.helix.extensions.plugin.PluginTaskIdentity> {
        val callId = request.binding.toolCallId
        val facts = dispatchFacts[callId]
        val descriptor = facts?.descriptor
        if (facts == null || descriptor == null) {
            error("approval card requested without dispatch facts for model call $callId")
        }
        pendingApprovals.register(approvalId, facts.turnId, callId)
        val sessionId = store.awaitingApproval(facts.turnId, callId)
        // Required disclosure is not best-effort: an undisplayable approval must fail closed.
        presentation.publish(
            ToolPresentationFact.ApprovalRequested(
                approvalId,
                ToolCallFact(
                    sessionId,
                    facts.turnId,
                    callId,
                    request.binding.toolName,
                    CanonicalArgs.canonicalize(facts.args),
                    null,
                ),
                request,
                descriptor,
                facts.args,
                facts.profile,
                facts.dataOrigin,
                facts.egress,
            ),
        )
        return descriptor to
            com.helix.extensions.plugin
                .PluginTaskIdentity(sessionId, facts.turnId)
    }

    override fun prepareModelCalls(
        calls: List<BufferedModelToolCall>,
        directory: com.helix.core.workspace.FileScopePath?,
        exposedTools: List<com.helix.core.model.ModelToolSchema>,
    ): List<BufferedModelToolCall> {
        val exposed =
            com.helix.core.model
                .ModelToolBindings(exposedTools)
        return calls.map { call ->
            val ref = exposed.resolve(call.name)
            val descriptor = ref?.let(toolPipeline.registry::resolveBinding)?.descriptor
            val extracted = ToolPresentationMetadata.extract(call.arguments)
            val arguments =
                if (directory != null && FileToolArguments.handles(descriptor)) {
                    FileToolArguments.bindRequest(extracted.businessArgumentsJson, directory, descriptor?.name?.value)
                } else {
                    extracted.businessArgumentsJson
                }
            call.copy(
                arguments = arguments,
                presentation = extracted.presentation,
                bindingRef = ref,
                bindingRequired = true,
            )
        }
    }

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
     * [ApprovalWaitCancelledException] AFTER all settled calls are persisted.
     *
     * This runs on the work scope's IO thread — the scheduler and the broker's blocking
     * user-decision wait never touch the main thread.
     */
    override fun loopProgress(turnId: String) = DurableToolLoopProgress(store, toolPipeline.registry).evaluate(turnId)

    override fun resetLoopProgress(turnId: String) = store.resetLoopProgress(turnId)

    @Suppress("TooGenericExceptionCaught") // settle every slot before propagating a batch failure
    override suspend fun executeBatch(
        request: com.helix.core.agent.ToolBatchRequest,
        observer: com.helix.core.agent.ToolBatchObserver,
    ): SettledBatch {
        val turn = store.batchTurn(request.turnId, request.sessionId, request.modelCallId)
        val calls = request.calls
        val control = request.control
        val prepareds = prepareBatchCalls(turn, calls, control, request.modelCallId)
        val requests = prepareds.mapNotNull { it.request }
        val batch =
            if (requests.isEmpty()) {
                ToolScheduler.BatchResult(emptyList())
            } else {
                toolPipeline.scheduler.scheduleBatch(requests)
            }
        var slot = 0
        var settlementFailure: Exception? = null
        val reviewCallIds = mutableListOf<String>()
        val settled =
            prepareds.map { p ->
                val settlement = if (p.preSettled == null) batch.settlements[slot++] else null
                val unknown = requiresSettlementReview(settlement)
                val outcome =
                    p.preSettled ?: when (settlement) {
                        is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                        is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
                        null -> error("Missing scheduled settlement")
                    }
                try {
                    val durationMs =
                        executionStartTimes.remove(p.callId)?.let { start ->
                            (clock.now().toEpochMilli() - start).coerceAtLeast(0L)
                        }
                    if (p.preSettled == null) {
                        outcomeStore.settleToolCall(
                            p.row!!.id,
                            p.callId,
                            p.toolNameRaw,
                            outcome,
                            unknown,
                            durationMs,
                        )
                    }
                    observer.settled(p.callId, sideEffectUnknown = unknown)
                    if (unknown) reviewCallIds += p.callId
                    publishFinishedCall(p.callId)
                } catch (failure: Exception) {
                    val first = settlementFailure
                    if (first == null) settlementFailure = failure else first.addSuppressed(failure)
                }
                SettledCall(p.callId, p.toolNameRaw, outcome)
            }
        settlementFailure?.let { failure ->
            batch.firstError?.let(failure::addSuppressed)
            throw failure
        }
        handleBatchError(batch.firstError, reviewCallIds)
        return SettledBatch(settled, reviewCallIds)
    }

    private fun prepareBatchCalls(
        turn: ToolDispatchTurn,
        calls: List<BufferedModelToolCall>,
        control: RunControlConfig,
        modelCallId: String,
    ): List<PreparedToolCall> =
        calls.mapIndexed { index, call ->
            prepareToolCall(
                turn,
                call.callId,
                call.name,
                call.arguments,
                call.presentation.modelIntent,
                control.mode,
                control.chatToolsEnabled,
                call.bindingRef,
                call.bindingRequired,
                ToolSettlementOrigin(modelCallId, index),
            )
        }

    private fun handleBatchError(
        error: Throwable?,
        reviewCallIds: List<String>,
    ) {
        error?.let {
            if (it is ApprovalWaitCancelledException) {
                // The turn is over (doc 11: cancel leaves a durable outcome for every
                // queued call — all slots above are settled). However an UNKNOWN sibling
                // wins this race: uncertain external state must be reviewed and cannot be
                // erased by a simultaneous clean approval cancellation.
                if (reviewCallIds.isEmpty()) throw it
                return
            }
            // Non-approval scheduler/executor failure was durably written as NEEDS_REVIEW.
            // It is normal parked control flow, not an exception for the generic INTERNAL catch.
            check(reviewCallIds.isNotEmpty()) { "batch error had no durable review settlement" }
        }
    }

    private fun requiresSettlementReview(settlement: ToolScheduler.BatchSettlement?): Boolean {
        val thrown = (settlement as? ToolScheduler.BatchSettlement.Thrown)?.cause
        return (thrown != null && thrown !is ApprovalWaitCancelledException) ||
            (
                (settlement as? ToolScheduler.BatchSettlement.Outcome)?.outcome
                    as? ToolDispatchOutcome.ExecutionFailed
            )?.requiresReview == true
    }

    private fun publishFinishedCall(callId: String) = emit(ToolPresentationFact.CallFinished(callId))

    private fun emit(fact: ToolPresentationFact) = publishToolProjection(fact.callId) { presentation.publish(fact) }

    /** One prepared tool call: the persisted row + dispatch request, or a pre-settled rejection. */
    private data class PreparedToolCall(
        val callId: String,
        val toolNameRaw: String,
        val row: ToolDispatchCall?,
        val request: ToolDispatchRequest?,
        val preSettled: ToolDispatchOutcome.Denied?,
    )

    /**
     * The per-call preparation of the tool pipeline (roadmap HXA-036/037; doc 11: the
     * Dispatcher is the only path between model-requested calls and registry):
     * validate the name/arguments, persist the tool_call row with the canonical bytes,
     * publish the timeline row, build the trusted dispatch request and register the card
     * facts. Malformed input the dispatcher can never see (an invalid tool name,
     * non-object arguments) is persisted + audited HERE as a stable
     * [ToolDispatchOutcome.Denied] (preSettled) — the dispatcher is never fed garbage.
     */
    private fun prepareToolCall(
        turn: ToolDispatchTurn,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        modelIntent: String?,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
        bindingRef: com.helix.core.model.ToolBindingRef? = null,
        bindingRequired: Boolean = false,
        origin: ToolSettlementOrigin? = null,
    ): PreparedToolCall {
        val businessArgs = rawArgsJson
        return if (store.reserveGoalCall(turn.id, toolCallId)) {
            prepareAdmittedToolCall(
                turn,
                toolCallId,
                toolNameRaw,
                businessArgs,
                modelIntent,
                mode,
                chatToolsEnabled,
                bindingRef,
                bindingRequired,
                origin,
            )
        } else {
            PreparedToolCall(
                toolCallId,
                toolNameRaw,
                null,
                null,
                outcomeStore.persistPreDispatchDenied(
                    turn.id,
                    toolCallId,
                    toolNameRaw,
                    businessArgs,
                    "unknown",
                    DispatchOutcomeCode.BUDGET_EXHAUSTED,
                    "Goal tool-call budget exhausted.",
                    PreDispatchDenialKind.FRAMEWORK_REJECTED,
                    modelIntent,
                    origin,
                ),
            )
        }
    }

    private fun prepareAdmittedToolCall(
        turn: ToolDispatchTurn,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        modelIntent: String?,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
        bindingRef: com.helix.core.model.ToolBindingRef? = null,
        bindingRequired: Boolean = false,
        origin: ToolSettlementOrigin? = null,
    ): PreparedToolCall {
        val selectedRef =
            bindingRef ?: if (!bindingRequired) {
                toolPipeline
                    .resolveLatest(
                        toolNameRaw,
                    )?.let { toolPipeline.registry.resolveBinding(it.name, it.version).ref }
            } else {
                null
            }
        val toolName = selectedRef?.name
        val descriptor = selectedRef?.let(toolPipeline.registry::resolveBinding)?.descriptor
        val args = parseToolArgs(rawArgsJson, descriptor, turn.sessionId)
        return invalidToolCallRejection(
            turn,
            toolCallId,
            toolNameRaw,
            rawArgsJson,
            modelIntent,
            toolName,
            args,
            descriptor,
            origin,
        )
            ?: prepareValidatedToolCall(
                turn,
                toolCallId,
                toolNameRaw,
                requireNotNull(toolName),
                (args as ToolArgumentPreparation.Ready).args,
                descriptor,
                modelIntent,
                mode,
                chatToolsEnabled,
                selectedRef,
                origin,
            )
    }

    @Suppress("LongParameterList")
    private fun prepareValidatedToolCall(
        turn: ToolDispatchTurn,
        toolCallId: String,
        toolNameRaw: String,
        toolName: ToolName,
        args: JsonObject,
        descriptor: ToolDescriptor?,
        modelIntent: String?,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
        bindingRef: com.helix.core.model.ToolBindingRef? = null,
        origin: ToolSettlementOrigin? = null,
    ): PreparedToolCall {
        val canonical = CanonicalArgs.canonicalize(args)
        recoveryEffectRejection(
            turn,
            toolCallId,
            toolNameRaw,
            canonical,
            descriptor,
            modelIntent,
            origin,
        )?.let { return it }
        val row =
            store.prepare(
                PrepareToolCall(
                    turn.id,
                    toolCallId,
                    toolNameRaw,
                    (bindingRef?.version ?: descriptor?.version)?.value?.toString() ?: "0",
                    canonical,
                    modelIntent,
                    origin,
                ),
            )
        val currentProfile = profile.value
        publishPreparedTool(row, turn.sessionId)
        val request =
            requests
                .build(
                    turn,
                    toolCallId,
                    toolName,
                    descriptor,
                    args,
                    currentProfile,
                    mode,
                    chatToolsEnabled,
                ).copy(
                    bindingRef = bindingRef,
                    bindingPinned = true,
                    onExecutionStarting = executionStarting(toolCallId, row),
                )
        dispatchFacts[toolCallId] =
            DispatchFacts(
                descriptor,
                args,
                currentProfile,
                request.dataOrigin,
                turn.id,
                request.egress,
            )
        return PreparedToolCall(toolCallId, toolNameRaw, row, request, null)
    }

    private fun publishPreparedTool(
        row: ToolDispatchCall,
        sessionId: String,
    ) {
        emit(ToolPresentationFact.Prepared(callFact(row, sessionId)))
    }

    private fun callFact(
        row: ToolDispatchCall,
        sessionId: String,
    ) = ToolCallFact(
        sessionId,
        row.turnId,
        row.callId,
        row.name,
        row.argsJson,
        row.modelIntent,
    )

    private fun executionStarting(
        toolCallId: String,
        row: ToolDispatchCall,
    ): () -> Unit =
        {
            executionStartTimes[toolCallId] = clock.now().toEpochMilli()
            emit(ToolPresentationFact.Running(store.running(row.id)))
        }

    private fun recoveryEffectRejection(
        turn: ToolDispatchTurn,
        toolCallId: String,
        toolNameRaw: String,
        canonicalArgs: String,
        descriptor: ToolDescriptor?,
        modelIntent: String?,
        origin: ToolSettlementOrigin?,
    ): PreparedToolCall? {
        val inspection = turn.recoveryInspection
        if (descriptor == null ||
            UnresolvedEffectPolicy.permitsInspection(inspection, descriptor.operationClass)
        ) {
            return null
        }
        val denied =
            outcomeStore.persistPreDispatchDenied(
                turnId = turn.id,
                toolCallId = toolCallId,
                toolNameRaw = toolNameRaw,
                rawArgs = canonicalArgs,
                version = descriptor.version.value.toString(),
                code = DispatchOutcomeCode.POLICY_DENIED,
                detail = "Unresolved external effects require review before further actions.",
                kind = PreDispatchDenialKind.RECOVERY_REVIEW_REQUIRED,
                modelIntent = modelIntent,
                origin = origin,
            )
        return PreparedToolCall(toolCallId, toolNameRaw, null, null, denied)
    }

    /**
     * Parse + normalize a tool call's raw arguments into the JSON object the dispatcher sees.
     * No-argument tools (e.g. time.now, whose ONLY valid input is {}) receive arguments as an
     * empty string or no argument fragments at all on many OpenAI-compatible servers (observed:
     * Ollama) — the decoders skip blank fragments, so the accumulated buffer ends up empty.
     * Normalize empty to the empty object: a tool that REQUIRES arguments still gets its precise
     * schema rejection (missing properties), instead of the misleading "not a valid JSON object"
     * for a call the model made correctly. File-scope paths are bound here, before hashing/approval.
     */
    private fun parseToolArgs(
        rawArgsJson: String,
        descriptor: ToolDescriptor?,
        sessionId: String,
    ): ToolArgumentPreparation.Result =
        ToolArgumentPreparation.prepare(rawArgsJson, descriptor) {
            FileToolArguments.directory(workspaceScopeId, store.directoryRef(sessionId))
        }

    /** The pre-settled Denied for an invalid tool name or rejected arguments; null when both are ready. */
    @Suppress("LongParameterList") // one parameter per validated fact; splitting the pair would obscure the invariant
    private fun invalidToolCallRejection(
        turn: ToolDispatchTurn,
        toolCallId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        modelIntent: String?,
        toolName: ToolName?,
        args: ToolArgumentPreparation.Result,
        descriptor: ToolDescriptor?,
        origin: ToolSettlementOrigin?,
    ): PreparedToolCall? =
        when {
            toolName == null -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    outcomeStore.persistPreDispatchDenied(
                        turn.id,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        "unknown",
                        DispatchOutcomeCode.UNKNOWN_TOOL,
                        "The requested tool binding is unavailable.",
                        PreDispatchDenialKind.FRAMEWORK_REJECTED,
                        modelIntent,
                        origin,
                    ),
                )
            }

            args is ToolArgumentPreparation.Rejected -> {
                PreparedToolCall(
                    toolCallId,
                    toolNameRaw,
                    null,
                    null,
                    outcomeStore.persistPreDispatchDenied(
                        turn.id,
                        toolCallId,
                        toolNameRaw,
                        rawArgsJson,
                        descriptor?.version?.value?.toString() ?: "unknown",
                        DispatchOutcomeCode.INVALID_ARGUMENTS,
                        args.detail,
                        PreDispatchDenialKind.FRAMEWORK_REJECTED,
                        modelIntent,
                        origin,
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
     * queued call gets a durable outcome) and rethrows [ApprovalWaitCancelledException] for
     * the turn-level handler.
     */
    override fun dispatchToolCall(
        toolCallId: String,
        turnId: String,
        toolNameRaw: String,
        rawArgsJson: String,
        mode: AgentMode,
        chatToolsEnabled: Boolean,
        parent: com.helix.tools.framework.ExecutableToolCall?,
        callbackCancel: com.helix.core.model.CancelSignal?,
    ): ToolDispatchOutcome {
        val turn = store.turn(turnId)
        parent?.let { recordScriptParent(it, toolCallId, turnId, turn.sessionId) }
        val extracted = ToolPresentationMetadata.extract(rawArgsJson)
        val initial =
            prepareToolCall(
                turn,
                toolCallId,
                toolNameRaw,
                extracted.businessArgumentsJson,
                extracted.presentation.modelIntent,
                mode,
                chatToolsEnabled,
            )
        val prepared = bindScriptBudget(initial, parent, callbackCancel)
        prepared.preSettled?.let { return it }
        val batch = toolPipeline.scheduler.scheduleBatch(listOf(prepared.request!!), parent?.toolCallId)
        val settlement = batch.settlements.single()
        val unknown = requiresSettlementReview(settlement)
        val outcome =
            when (settlement) {
                is ToolScheduler.BatchSettlement.Outcome -> settlement.outcome
                is ToolScheduler.BatchSettlement.Thrown -> unsettledSlotSettlement(settlement.cause)
            }
        val durationMs =
            executionStartTimes.remove(toolCallId)?.let { start ->
                (clock.now().toEpochMilli() - start).coerceAtLeast(0L)
            }
        outcomeStore.settleToolCall(
            prepared.row!!.id,
            toolCallId,
            toolNameRaw,
            outcome,
            unknown,
            durationMs,
        )
        batch.firstError?.let { error ->
            throw error
        }
        return outcome
    }

    /** Generated code supplies only business arguments; original mode, session and cancellation are host facts. */
    override fun dispatchScriptToolCall(
        parent: com.helix.tools.framework.ExecutableToolCall,
        id: String,
        tool: String,
        arguments: kotlinx.serialization.json.JsonObject,
        cancel: com.helix.core.model.CancelSignal,
    ): ToolDispatchOutcome =
        dispatchToolCall(
            id,
            requireNotNull(parent.turnId),
            tool,
            arguments.toString(),
            parent.agentMode,
            parent.chatToolsEnabled,
            parent,
            cancel,
        )

    private fun recordScriptParent(
        parent: com.helix.tools.framework.ExecutableToolCall,
        toolCallId: String,
        turnId: String,
        sessionId: String,
    ) {
        require(parent.sessionId == sessionId && parent.turnId == turnId) { "SCRIPT_OWNER_MISMATCH" }
        store.recordScriptParent(
            ScriptParentCommand(
                sessionId,
                turnId,
                toolCallId,
                parent.toolCallId,
                parent.toolName,
                parent.hostToolBridge?.retainingExecution == true,
            ),
        )
    }

    private fun bindScriptBudget(
        initial: PreparedToolCall,
        parent: com.helix.tools.framework.ExecutableToolCall?,
        callbackCancel: com.helix.core.model.CancelSignal?,
    ): PreparedToolCall =
        if (parent == null || initial.request == null) {
            initial
        } else {
            val request = requireNotNull(initial.request)
            initial.copy(
                request =
                    request.copy(
                        cancel =
                            object : com.helix.core.model.CancelSignal {
                                override fun isCancelled() =
                                    request.cancel.isCancelled() || parent.cancel.isCancelled() ||
                                        callbackCancel?.isCancelled() == true ||
                                        !clock.now().isBefore(
                                            parent.hostToolBridge?.executionDeadline ?: parent.deadline,
                                        )
                            },
                        remainingExecutionMillis = {
                            minOf(
                                request.remainingExecutionMillis(),
                                java.time.Duration
                                    .between(
                                        clock.now(),
                                        parent.hostToolBridge?.executionDeadline ?: parent.deadline,
                                    ).toMillis()
                                    .coerceAtLeast(0),
                            )
                        },
                    ),
            )
        }

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
        if (error is ApprovalWaitCancelledException) {
            ToolDispatchOutcome.Cancelled
        } else {
            ToolDispatchOutcome.ExecutionFailed(
                DispatchOutcomeCode.TOOL_FAILED,
                "Tool execution interrupted by orchestrator: ${error?.javaClass?.simpleName ?: "Unknown"}",
            )
        }

    private companion object {
        const val TAG = "HelixChatTools"
    }
}

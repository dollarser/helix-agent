package com.helix.core.agent

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.LocalToolCallBatch
import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.PromptSnapshot
import com.helix.core.agent.RequestBudgetDiagnostics
import com.helix.core.agent.ResponseInputBoundary
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.ToolLoopProgress
import com.helix.core.agent.TurnBudgetTracker
import com.helix.core.agent.TurnContextAssembler
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.TurnLoopResult
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.model.Clock
import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnState
import kotlinx.coroutines.withContext

/** The sole model/tool loop, runnable with protocol-neutral providers and no Android or Room. */
@Suppress("LongParameterList") // Independent narrow ports preserve the existing single-owner domains.
class AgentLoop(
    private val runtimeAccounting: AgentLoopAccounting,
    private val provenance: RequestProvenanceStore,
    private val models: AgentModelAccess,
    private val contextAssembler: TurnContextAssembler,
    private val toolExecutor: AgentToolGateway,
    private val toolMessages: ToolMessageMaterializer,
    private val goalBudget: GoalModelBudget,
    private val compactionCycles: ContextCompactionCycles,
    private val execution: LoopExecutionControl,
    private val notices: LoopNotices,
    private val events: AgentLoopEvents,
    private val clock: Clock,
    private val idGenerator: () -> String,
) {
    enum class Entry { INITIAL, BACKFILL }

    /**
     * The multi-step tool loop (roadmap HXA-037; doc 11 sections 3/5): model step →
     * (bounded-parallel) tool round → results settled IN CALL SEQUENCE → persisted
     * (`model-visible ⇔ persisted`) → back-filled into the next model request → repeat
     * until the model stops calling tools, a step fails, the user stops, or the turn's
     * tool-round budget is exhausted (fail closed).
     *
     * Every model step gets its own `model_calls` row; every tool call gets its durable
     * outcome through the dispatcher (cancel/recovery invariants — doc 11 section 7).
     * One loop retains the budget tracker across compaction, tool batches and user steering.
     */
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    suspend fun runToolLoop(
        sessionId: String,
        coordinator: AgentTurnJournal,
        providerId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        entry: Entry = Entry.INITIAL,
        inputDelivery: AgentInputBoundary? = null,
    ): TurnLoopResult {
        val turnId = coordinator.id
        val runtimeRecord = runtimeAccounting.restore(turnId, providerId, control)
        var context =
            when (entry) {
                Entry.INITIAL -> contextAssembler.build(sessionId, turnId, retryTurnId, control)
                Entry.BACKFILL -> contextAssembler.buildBackfill(sessionId, turnId, control)
            }
        runtimeRecord?.let { require(context.model == it.modelId) { "Turn runtime model drift" } }
        val provider = models.modelProviderFor(providerId, context.model)
        var manualCommandPending = context.messages.lastOrNull()?.text == ContextCommands.COMPACT
        var compactionRound = compactionCycles.create(sessionId, turnId, providerId, context, control)
        var toolRounds = runtimeRecord?.admittedToolRounds ?: 0
        var finalResponsePending = false
        var modelRetries = 0
        val budgetTracker =
            runtimeRecord?.let {
                TurnBudgetTracker.restore(
                    control.budgets,
                    consumedModelCalls = it.consumedModelCalls,
                    consumedTokens = it.consumedTokens,
                )
            } ?: TurnBudgetTracker(control.budgets)
        val contextSettings = models.contextSettings(providerId, context.model)
        val window = contextSettings.window
        while (true) {
            execution.checkActive(turnId)
            if (execution.isCancelled(turnId)) {
                return TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            }
            if (!manualCommandPending && (inputDelivery?.appendBeforeRequest(sessionId, turnId) == true)) {
                toolExecutor.resetLoopProgress(turnId)
                context = contextAssembler.buildBackfill(sessionId, turnId, control)
            }
            if (finalResponsePending) context = context.copy(tools = emptyList())
            coordinator.recordDiagnostic(
                "budget.request",
                RequestBudgetDiagnostics.request(
                    context,
                    control.budgets,
                    window,
                    budgetTracker,
                    compactionRound.admissionInput(context),
                    contextSettings.windowSource,
                ),
            )
            val prepared = compactionRound.prepare(context)
            prepared.failure?.let {
                coordinator.recordDiagnostic(
                    "budget.result",
                    RequestBudgetDiagnostics.result(it.errorCode, null, budgetTracker),
                )
                return TurnLoopResult.Terminal(it)
            }
            val compaction = prepared.plan
            val admission =
                ModelLoopAdmission.prepare(
                    requireNotNull(prepared.request),
                    turnId,
                    coordinator.snapshot().modelCallId,
                    budgetTracker,
                    goalBudget,
                )
            admission.failure?.let {
                coordinator.recordDiagnostic(
                    "budget.result",
                    RequestBudgetDiagnostics.result(it.errorCode, null, budgetTracker),
                )
                return TurnLoopResult.Terminal(it)
            }
            val request = requireNotNull(admission.request)
            runtimeAccounting.checkpointModelAdmission(turnId, budgetTracker)
            coordinator.recordDiagnostic(
                "budget.admitted",
                RequestBudgetDiagnostics.admitted(
                    request,
                    compaction != null,
                ),
            )
            // The per-request prompt record commits inside collectModelStream, before the wire call.
            val manifestJson =
                createRequestManifest(
                    coordinator.snapshot().modelCallId,
                    context,
                    compaction,
                    request,
                    turnId,
                )
            context.workspaceBinding?.let {
                provenance.recordWorkspace(coordinator.snapshot().modelCallId, it)
            }
            val acc =
                collectModelStream(
                    coordinator,
                    provider,
                    request,
                    compaction == null,
                    context.prompt,
                    sessionId,
                    if (compaction == null) context.sourceMessageIds else emptySet(),
                    manifestJson,
                    inputDelivery,
                )
            val decision = acc.terminal(execution.isCancelled(turnId))
            val accountingFailure = admission.finish(acc)
            runtimeAccounting.checkpointTokens(turnId, budgetTracker)
            coordinator.recordDiagnostic(
                "budget.result",
                RequestBudgetDiagnostics.result(accountingFailure?.errorCode ?: decision.errorCode, acc, budgetTracker),
            )
            accountingFailure?.let { return TurnLoopResult.Terminal(it) }
            val retryDelay =
                ModelRecoveryPolicy.retryDelayMillis(
                    provider is com.helix.provider.api.WireModelProvider,
                    modelRetries,
                    acc,
                )
            if (retryDelay != null && decision.state == TurnState.FAILED) {
                coordinator.recordDiagnostic("model.automatic_retry", "{\"attempt\":${modelRetries + 1}}")
                coordinator.retryEmptyModelStream(idGenerator())
                modelRetries += 1
                kotlinx.coroutines.delay(retryDelay)
                continue
            }
            if (finalResponsePending && compaction == null) {
                // Never dispatch even if a provider ignores the empty schema and emits a call.
                val terminal =
                    if (decision.state == TurnState.COMPLETED) {
                        ModelStreamTerminal(TurnState.FAILED, "TOOL_LOOP_NO_PROGRESS")
                    } else {
                        decision
                    }
                return TurnLoopResult.Terminal(terminal)
            }
            if (compaction != null) {
                val finished =
                    compactionRound
                        .finish(
                            compaction,
                            acc,
                            decision,
                            coordinator,
                            idGenerator(),
                            notices.text(LoopNotice.COMPACTED),
                            notices.text(LoopNotice.COMPACTION_UNCHANGED),
                        )
                if (finished != null) {
                    if (finished.state != TurnState.COMPLETED) return TurnLoopResult.Terminal(finished)
                    finishResponseOrReturn(sessionId, coordinator, finished, inputDelivery)?.let {
                        return TurnLoopResult.Terminal(it)
                    }
                    context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    manualCommandPending = context.messages.lastOrNull()?.text == ContextCommands.COMPACT
                    // The explicit compaction command has finished; the accepted user input is
                    // an ordinary request in this same Turn, retaining its budget tracker.
                    compactionRound = compactionCycles.create(sessionId, turnId, providerId, context, control)
                }
                events.emit(AgentLoopEvent.Refresh)
                context = contextAssembler.rebuild(sessionId, turnId, retryTurnId, control, context)
            } else {
                if (decision.state != TurnState.COMPLETED) return TurnLoopResult.Terminal(decision)
                compactionRound.observe(context, acc.inputTokens)
                when (
                    val round =
                        runToolRound(
                            sessionId,
                            coordinator,
                            acc,
                            toolRounds,
                            control,
                            context.directory,
                            request.tools,
                        )
                ) {
                    is ToolRoundLimit -> {
                        coordinator.recordDiagnostic(
                            "budget.result",
                            RequestBudgetDiagnostics.result("TOOL_STEP_LIMIT", acc, budgetTracker),
                        )
                        return TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.FAILED, "TOOL_STEP_LIMIT"))
                    }

                    is ToolRoundContinued -> {
                        toolRounds = round.toolRounds
                        context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    }

                    is ToolRoundReviewRequired -> {
                        return TurnLoopResult.ParkedForReview(round.callIds)
                    }

                    is ToolRoundNoProgress -> {
                        finalResponsePending = true
                        context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    }

                    else -> {
                        finishResponseOrReturn(sessionId, coordinator, decision, inputDelivery)?.let {
                            return TurnLoopResult.Terminal(it)
                        }
                        context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    }
                }
            }
        }
    }

    private suspend fun finishResponseOrReturn(
        sessionId: String,
        coordinator: AgentTurnJournal,
        decision: ModelStreamTerminal,
        inputDelivery: AgentInputBoundary?,
    ): ModelStreamTerminal? =
        if (inputDelivery == null) {
            decision
        } else {
            when (inputDelivery.finishResponse(sessionId, coordinator.id)) {
                ResponseInputBoundary.CANCELLED -> {
                    ModelStreamTerminal(TurnState.CANCELLED, null)
                }

                ResponseInputBoundary.TERMINAL -> {
                    decision.copy(state = coordinator.snapshot().phase)
                }

                else -> {
                    events.emit(AgentLoopEvent.Refresh)
                    null
                }
            }
        }

    private suspend fun createRequestManifest(
        modelCallId: String,
        context: TurnContextRequest,
        compaction: ContextCompactionPlan?,
        request: ModelRequest,
        turnId: String,
    ): String {
        val manifest =
            if (compaction == null) {
                val inputIds: List<String> =
                    if (context.sourceMessageIds.isNotEmpty()) {
                        provenance.inputIds(turnId, context.sourceMessageIds)
                    } else {
                        emptyList()
                    }
                CompactManifestCodec.bounded(
                    callId = modelCallId,
                    timestamp = clock.now().toEpochMilli(),
                    checkpoint = context.checkpoint,
                    messages = context.messageRefs,
                    inputIds = inputIds,
                    tools =
                        com.helix.core.model
                            .ModelToolBindings(request.tools)
                            .entries,
                )
            } else {
                CompactManifestCodec.bounded(
                    callId = modelCallId,
                    timestamp = clock.now().toEpochMilli(),
                    checkpoint = compaction.coveredThrough,
                    messages =
                        request.messages.mapIndexed { idx, msg ->
                            MessageRefEntry(
                                messageId = "summary-msg-$idx",
                                roleCode = MessageRefEntry.fromModelRole(msg.role),
                            )
                        },
                    inputIds = emptyList(),
                )
            }
        return CompactManifestCodec.encodeCompact(manifest)
    }

    @Suppress("LongParameterList")
    private suspend fun collectModelStream(
        coordinator: AgentTurnJournal,
        provider: com.helix.provider.api.ModelProvider,
        request: ModelRequest,
        publishText: Boolean = true,
        prompt: PromptSnapshot? = null,
        sessionId: String,
        sourceMessageIds: Set<String>,
        manifestJson: String? = null,
        inputDelivery: AgentInputBoundary?,
    ): ModelStreamState {
        // Per-request prompt record (research doc section 4.4): commits to THIS model-call row
        // plus its audit event before the wire call; summary calls carry no record.
        coordinator.recordPromptSnapshot(prompt, !publishText)
        coordinator.recordRequestManifest(manifestJson)
        val acc = coordinator.beginModelStream(compacting = !publishText)
        execution.checkActive(coordinator.id)
        if (publishText) {
            coordinator.recordInputRequestStarted(sourceMessageIds)
            inputDelivery?.requestStarting(
                sessionId,
                coordinator.id,
                coordinator.snapshot().modelCallId,
                sourceMessageIds,
            )
        }
        kotlinx.coroutines.withContext(
            com.helix.core.agent
                .LocalModelCallContext(coordinator.id, coordinator.snapshot().modelCallId),
        ) {
            provider.stream(request).collect {
                execution.checkActive(coordinator.id)
                val update = acc.apply(it)
                if (publishText && update.textChanged) {
                    events.emit(AgentLoopEvent.TextChanged(coordinator.id, acc.text))
                }
            }
        }
        return acc
    }

    /** The one tool round of [runToolLoop]: continued, budget-limited, or none (no finished calls). */
    private sealed class ToolRoundResult

    private class ToolRoundContinued(
        val toolRounds: Int,
    ) : ToolRoundResult()

    private class ToolRoundLimit : ToolRoundResult()

    private class ToolRoundNoProgress : ToolRoundResult()

    private class ToolRoundReviewRequired(
        val callIds: List<String>,
    ) : ToolRoundResult()

    /**
     * Runs ONE tool round when the decision is COMPLETED with finished tool calls: closes
     * the model step's row, persists the assistant's tool-call step, runs the batch
     * (bounded parallel execution, deterministic call-order settlement), persists the
     * results in the SAME call sequence, and opens the next model step's row.
     * [ToolRoundLimit] when the turn's tool-round budget is exhausted (fail closed — the
     * turn ends FAILED with the safe label rather than silently truncating the work);
     * null when there is no finished call to run (the model's final answer).
     */
    @Suppress("ReturnCount") // one early return per guard (no finished call / budget limit)
    private suspend fun runToolRound(
        sessionId: String,
        coordinator: AgentTurnJournal,
        acc: ModelStreamState,
        toolRounds: Int,
        control: RunControlConfig,
        directory: com.helix.core.workspace.FileScopePath?,
        exposedTools: List<com.helix.core.model.ModelToolSchema>,
    ): ToolRoundResult? {
        val turnId = coordinator.id
        val calls = acc.finishedToolCalls
        if (calls.isEmpty()) return null
        if (toolRounds >= control.budgets.maxSteps) return ToolRoundLimit()
        runtimeAccounting.checkpointToolRound(turnId, toolRounds)
        val localBatch = LocalToolCallBatch(toolExecutor.prepareModelCalls(calls, directory, exposedTools), idGenerator)
        coordinator.beginToolBatch(localBatch.calls.map { it.callId })
        coordinator.commitModelToolStep(toolMessages.assistantToolStepJson(localBatch))
        val settled =
            toolExecutor.executeBatch(
                com.helix.core.agent
                    .ToolBatchRequest(sessionId, turnId, localBatch.calls, control),
                com.helix.core.agent
                    .ToolBatchObserver(coordinator::settleBatchCall),
            )
        check(
            settled.calls.map { it.callId } == localBatch.calls.map { it.callId },
        ) { "TOOL_BATCH_RESULT_IDENTITY_MISMATCH" }
        if (settled.requiresReview) return ToolRoundReviewRequired(settled.reviewCallIds)
        val progress = toolExecutor.loopProgress(turnId)
        val nextCallId = idGenerator()
        coordinator.openNextModelCall(
            listOfNotNull(toolMessages.progressDraft(progress)) +
                settled.calls.map {
                    toolMessages.toolResultDraft(
                        it.copy(callId = localBatch.wireId(it.callId), resultReference = "$turnId/${it.callId}"),
                    )
                },
            nextCallId,
        )
        if (progress == ToolLoopProgress.Decision.STOP) return ToolRoundNoProgress()
        return ToolRoundContinued(toolRounds + 1)
    }
}

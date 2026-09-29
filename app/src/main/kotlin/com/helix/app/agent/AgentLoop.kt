package com.helix.app.agent

import com.helix.app.R
import com.helix.app.engine.TurnRuntimeAccounting
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.agent.PromptSnapshot
import com.helix.core.model.Clock
import com.helix.core.model.CompactManifestCodec
import com.helix.core.model.MessageRefEntry
import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.withContext

/**
 * The unified agent loop (research doc section 34; HX2-02, de-Chat-ified from the old
 * `ChatModelLoop`): owns model steps, compaction admission and ordered tool rounds within one
 * admitted Turn — `step / tool / step`, nothing else. It is chat-free by construction: context
 * construction ([TurnContextAssembler]) and tool execution ([TurnToolExecutor]) are ports the
 * host provides (production: the chat-layer request assembler / tool execution), so every
 * producer (Chat / Goal / Share / Voice / Widget / Channel) drives the SAME loop.
 */
@Suppress("LongParameterList")
internal class AgentLoop(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val contextAssembler: TurnContextAssembler,
    private val toolExecutor: TurnToolExecutor,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val liveHandles: TurnExecutionHandles,
    private val strings: (Int, Array<out Any>) -> String,
    private val refreshScreen: () -> Unit,
    private val applyEvent: (com.helix.core.model.ModelEvent, ModelStreamState, String) -> Unit,
    private val persistTerminalInTransaction: (TurnTerminalCheckpoint, ModelStreamTerminal) -> ModelStreamTerminal,
    private val inputDelivery: TurnInputDelivery? = null,
) {
    private val runtimeAccounting = TurnRuntimeAccounting(storage)

    internal enum class Entry {
        INITIAL,
        BACKFILL,
    }

    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    suspend fun runWithGoalTime(
        turnId: String,
        block: suspend () -> TurnLoopResult,
    ): TurnLoopResult {
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return block()
        val timer = GoalTimeBudget(storage, clock, binding.runId)
        liveHandles.installGoalTime(turnId, timer)
        try {
            return timer.run(
                onExpiry = {
                    requireNotNull(liveHandles.cancelSignal(turnId)) { "TURN_NOT_LIVE: $turnId" }.cancel()
                },
                completionWins = { it is TurnLoopResult.ParkedForReview },
                block = block,
            )
        } finally {
            liveHandles.clearGoalTime(turnId, timer)
        }
    }

    private suspend fun compactionRound(
        sessionId: String,
        turnId: String,
        providerId: String,
        context: ChatContextRequest,
        control: RunControlConfig,
    ): ContextCompactionRound =
        ContextCompactionRound(
            storage,
            sessionId,
            turnId,
            control,
            providerService.contextSettings(providerId, context.model),
            context.messages.lastOrNull()?.text == ContextCompaction.COMMAND,
            providerService.resolveReasoning(providerId, context.model, com.helix.core.model.ReasoningEffort.LOW),
        )

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
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        entry: Entry = Entry.INITIAL,
    ): TurnLoopResult {
        val turnId = coordinator.id
        val provider = providerService.modelProviderFor(providerId)
        val runtimeRecord = storage.turnRuntimeRecords.find(turnId)
        runtimeRecord?.let { runtimeAccounting.validate(it, providerId, control) }
        var context =
            when (entry) {
                Entry.INITIAL -> contextAssembler.build(sessionId, turnId, retryTurnId, control)
                Entry.BACKFILL -> contextAssembler.buildBackfill(sessionId, turnId, control)
            }
        runtimeRecord?.let { require(context.model == it.modelId) { "Turn runtime model drift" } }
        var manualCommandPending = context.messages.lastOrNull()?.text == ContextCompaction.COMMAND
        var compactionRound = compactionRound(sessionId, turnId, providerId, context, control)
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
        val goalBudget = GoalModelCallBudget(storage, clock)
        val contextSettings = providerService.contextSettings(providerId, context.model)
        val window = contextSettings.window
        while (true) {
            liveHandles.goalTime(turnId)?.checkActive()
            if (liveHandles.cancelSignal(turnId)?.isCancelled() == true) {
                return TurnLoopResult.Terminal(ModelStreamTerminal(TurnState.CANCELLED, null))
            }
            if (!manualCommandPending && appendQueuedSteering(sessionId, coordinator)) {
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
                storage.workspaces.recordRequest(coordinator.snapshot().modelCallId, it)
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
                )
            val decision = acc.terminal(liveHandles.cancelSignal(turnId)?.isCancelled() == true)
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
                            str(R.string.context_compacted),
                        )
                if (finished != null) {
                    if (finished.state != TurnState.COMPLETED) return TurnLoopResult.Terminal(finished)
                    finishResponseOrReturn(sessionId, coordinator, finished)?.let {
                        return TurnLoopResult.Terminal(it)
                    }
                    context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    manualCommandPending = context.messages.lastOrNull()?.text == ContextCompaction.COMMAND
                    // The explicit compaction command has finished; the accepted user input is
                    // an ordinary request in this same Turn, retaining its budget tracker.
                    compactionRound = compactionRound(sessionId, turnId, providerId, context, control)
                }
                refreshScreen()
                context = contextAssembler.rebuild(sessionId, turnId, retryTurnId, control, context)
            } else {
                if (decision.state != TurnState.COMPLETED) return TurnLoopResult.Terminal(decision)
                compactionRound.observe(context, acc.inputTokens)
                when (
                    val round =
                        runToolRound(
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
                        finishResponseOrReturn(sessionId, coordinator, decision)?.let {
                            return TurnLoopResult.Terminal(it)
                        }
                        context = contextAssembler.buildBackfill(sessionId, turnId, control)
                    }
                }
            }
        }
    }

    private suspend fun appendQueuedSteering(
        sessionId: String,
        coordinator: TurnCoordinator,
    ): Boolean {
        var updated = false
        val delivery = inputDelivery
        if (delivery != null) {
            var remaining = 32
            while (remaining-- > 0) {
                val input = delivery.prepareSteering(sessionId, coordinator.id) ?: break
                updated = coordinator.appendSteeringBeforeRequest(input) || updated
            }
        }
        return updated
    }

    private suspend fun finishResponseOrReturn(
        sessionId: String,
        coordinator: TurnCoordinator,
        decision: ModelStreamTerminal,
    ): ModelStreamTerminal? =
        if (inputDelivery == null) {
            decision
        } else {
            when (finishResponse(sessionId, coordinator)) {
                ResponseInputBoundary.CANCELLED -> {
                    ModelStreamTerminal(TurnState.CANCELLED, null)
                }

                ResponseInputBoundary.TERMINAL -> {
                    decision.copy(state = coordinator.snapshot().phase)
                }

                else -> {
                    refreshScreen()
                    null
                }
            }
        }

    private suspend fun finishResponse(
        sessionId: String,
        coordinator: TurnCoordinator,
    ): ResponseInputBoundary {
        var boundary: ResponseInputBoundary
        do {
            val steering = inputDelivery?.prepareSteering(sessionId, coordinator.id)
            boundary = coordinator.completeResponseOrContinue(steering, idGenerator(), persistTerminalInTransaction)
            if (boundary == ResponseInputBoundary.RECHECK) kotlinx.coroutines.yield()
        } while (boundary == ResponseInputBoundary.RECHECK)
        return boundary
    }

    private fun createRequestManifest(
        modelCallId: String,
        context: ChatContextRequest,
        compaction: ContextCompaction.Plan?,
        request: ModelRequest,
        turnId: String,
    ): String {
        val manifest =
            if (compaction == null) {
                val inputIds: List<String> =
                    if (context.sourceMessageIds.isNotEmpty()) {
                        storage.sessionInputs.appendedForMessages(turnId, context.sourceMessageIds).map { it.inputId }
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
        coordinator: TurnCoordinator,
        provider: com.helix.provider.api.ModelProvider,
        request: ModelRequest,
        publishText: Boolean = true,
        prompt: PromptSnapshot? = null,
        sessionId: String,
        sourceMessageIds: Set<String>,
        manifestJson: String? = null,
    ): ModelStreamState {
        // Per-request prompt record (research doc section 4.4): commits to THIS model-call row
        // plus its audit event before the wire call; summary calls carry no record.
        coordinator.recordPromptSnapshot(prompt, !publishText)
        coordinator.recordRequestManifest(manifestJson)
        val acc = coordinator.beginModelStream(compacting = !publishText)
        liveHandles.goalTime(coordinator.id)?.checkActive()
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
            com.helix.app.provider
                .LocalModelCallContext(coordinator.id, coordinator.snapshot().modelCallId),
        ) {
            provider.stream(request).collect {
                if (publishText) {
                    applyEvent(it, acc, coordinator.id)
                } else {
                    liveHandles.goalTime(coordinator.id)?.checkActive()
                    acc.apply(it)
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
        coordinator: TurnCoordinator,
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
        coordinator.commitModelToolStep(toolExecutor.assistantToolStepJson(localBatch))
        val turn = storage.turns.resolve(turnId)
        val settled = toolExecutor.runToolBatch(turn, turnId, localBatch.calls, coordinator, control)
        if (settled.requiresReview) return ToolRoundReviewRequired(settled.reviewCallIds)
        val progress = toolExecutor.loopProgress(turnId)
        val nextCallId = idGenerator()
        coordinator.openNextModelCall(
            (
                if (progress != ToolLoopProgress.Decision.CONTINUE) {
                    listOf(
                        TurnMessageDraft(
                            com.helix.core.model.ModelRole.SYSTEM,
                            if (progress == ToolLoopProgress.Decision.STOP) "loop_exhausted" else "loop_warning",
                            if (progress ==
                                ToolLoopProgress.Decision.STOP
                            ) {
                                ToolLoopProgress.EXHAUSTED
                            } else {
                                ToolLoopProgress.WARNING
                            },
                        ),
                    )
                } else {
                    emptyList()
                }
            ) +
                settled.calls.map {
                    toolExecutor.toolResultDraft(
                        it.copy(callId = localBatch.wireId(it.callId), resultReference = "$turnId/${it.callId}"),
                    )
                },
            nextCallId,
        )
        if (progress == ToolLoopProgress.Decision.STOP) return ToolRoundNoProgress()
        return ToolRoundContinued(toolRounds + 1)
    }
}

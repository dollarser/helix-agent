package com.helix.app.chat

import com.helix.app.R
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.Clock
import com.helix.core.model.ModelRequest
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import kotlinx.coroutines.withContext

/** Owns model steps, compaction admission and ordered tool rounds within one admitted Turn. */
@Suppress("LongParameterList")
internal class ChatModelLoop(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val requestAssembler: ChatRequestAssembler,
    private val toolCalls: ChatToolCalls,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val goalTimes: java.util.concurrent.ConcurrentHashMap<String, GoalTimeBudget>,
    private val turnCancels: java.util.concurrent.ConcurrentHashMap<String, TurnCancelSignal>,
    private val strings: (Int, Array<out Any>) -> String,
    private val refreshScreen: () -> Unit,
    private val applyEvent: (com.helix.core.model.ModelEvent, ModelStreamState, String) -> Unit,
) {
    private fun str(
        resId: Int,
        vararg args: Any,
    ): String = strings(resId, args)

    suspend fun runWithGoalTime(
        turnId: String,
        block: suspend () -> ModelStreamTerminal,
    ): ModelStreamTerminal {
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return block()
        val timer = GoalTimeBudget(storage, clock, binding.runId)
        goalTimes[turnId] = timer
        try {
            return timer.run(
                onExpiry = {
                    turnCancels
                        .getOrPut(
                            turnId,
                        ) { TurnCancelSignal { timer.expiredCode() != null } }
                        .cancel()
                },
                block = block,
            )
        } finally {
            goalTimes.remove(turnId)
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
            if (providerService.capabilitiesFor(providerId)?.reasoning == true) {
                com.helix.core.model.ReasoningEffort.LOW
            } else {
                com.helix.core.model.ReasoningEffort.OFF
            },
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
     */
    @Suppress("ReturnCount") // one early return per terminal condition of the loop (cancel / non-completed / budget)
    suspend fun runToolLoop(
        sessionId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        control: RunControlConfig,
    ): ModelStreamTerminal {
        val turnId = coordinator.id
        val provider = providerService.modelProviderFor(providerId)
        var context = requestAssembler.buildRequest(sessionId, retryTurnId, control)
        val compactionRound = compactionRound(sessionId, turnId, providerId, context, control)
        var toolRounds = 0
        val budgetTracker = TurnBudgetTracker(control.budgets)
        val goalBudget = GoalModelCallBudget(storage, clock)
        while (true) {
            goalTimes[turnId]?.checkActive()
            if (turnCancels[turnId]?.isCancelled() == true) {
                return ModelStreamTerminal(TurnState.CANCELLED, null)
            }
            val prepared = compactionRound.prepare(context)
            prepared.failure?.let { return it }
            val compaction = prepared.plan
            val admission =
                ModelLoopAdmission.prepare(
                    requireNotNull(prepared.request),
                    turnId,
                    coordinator.snapshot().modelCallId,
                    budgetTracker,
                    goalBudget,
                )
            admission.failure?.let { return it }
            val request = requireNotNull(admission.request)
            val acc = collectModelStream(coordinator, provider, request, compaction == null)
            val decision = acc.terminal(turnCancels[turnId]?.isCancelled() == true)
            admission.finish(acc)?.let { return it }
            if (compaction != null) {
                compactionRound
                    .finish(
                        compaction,
                        acc,
                        decision,
                        coordinator,
                        idGenerator(),
                        str(R.string.context_compacted),
                    )?.let { return it }
                refreshScreen()
                context = requestAssembler.rebuild(sessionId, retryTurnId, control, context)
            } else {
                if (decision.state != TurnState.COMPLETED) return decision
                compactionRound.observe(context, acc.inputTokens)
                when (val round = runToolRound(coordinator, acc, toolRounds, control)) {
                    is ToolRoundLimit -> {
                        return ModelStreamTerminal(TurnState.FAILED, "TOOL_STEP_LIMIT")
                    }

                    is ToolRoundContinued -> {
                        toolRounds = round.toolRounds
                        context = requestAssembler.buildBackfillRequest(sessionId, control)
                    }

                    else -> {
                        return decision
                    }
                }
            }
        }
    }

    private suspend fun collectModelStream(
        coordinator: TurnCoordinator,
        provider: com.helix.provider.api.ModelProvider,
        request: ModelRequest,
        publishText: Boolean = true,
    ): ModelStreamState {
        val acc = coordinator.beginModelStream(compacting = !publishText)
        goalTimes[coordinator.id]?.checkActive()
        kotlinx.coroutines.withContext(
            com.helix.app.provider
                .LocalModelCallContext(coordinator.id, coordinator.snapshot().modelCallId),
        ) {
            provider.stream(request).collect {
                if (publishText) {
                    applyEvent(it, acc, coordinator.id)
                } else {
                    goalTimes[coordinator.id]?.checkActive()
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
    ): ToolRoundResult? {
        val turnId = coordinator.id
        val calls = acc.finishedToolCalls
        if (calls.isEmpty()) return null
        if (toolRounds >= control.budgets.maxSteps) return ToolRoundLimit()
        val localBatch = LocalToolCallBatch(calls, idGenerator)
        coordinator.beginToolBatch(localBatch.calls.map { it.callId })
        coordinator.commitModelToolStep(toolCalls.assistantToolStepJson(localBatch))
        val turn = storage.turns.resolve(turnId)
        val settled = toolCalls.runToolBatch(turn, turnId, localBatch.calls, coordinator, control)
        val nextCallId = idGenerator()
        coordinator.openNextModelCall(
            settled.map { toolCalls.toolResultDraft(it.copy(callId = localBatch.wireId(it.callId))) },
            nextCallId,
        )
        return ToolRoundContinued(toolRounds + 1)
    }
}

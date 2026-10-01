package com.helix.app.agent

import com.helix.app.R
import com.helix.app.engine.TurnRuntimeAccounting
import com.helix.app.provider.ProviderService
import com.helix.core.agent.AgentLoop
import com.helix.core.agent.AgentLoopEvent
import com.helix.core.agent.AgentLoopEvents
import com.helix.core.agent.AgentModelAccess
import com.helix.core.agent.AgentToolGateway
import com.helix.core.agent.ContextCommands
import com.helix.core.agent.ContextCompactionCycles
import com.helix.core.agent.LoopExecutionControl
import com.helix.core.agent.LoopNotice
import com.helix.core.agent.LoopNotices
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.ToolMessageMaterializer
import com.helix.core.agent.TurnContextAssembler
import com.helix.core.agent.TurnLoopResult
import com.helix.core.agent.TurnTerminalCheckpoint
import com.helix.core.model.Clock
import com.helix.core.model.ReasoningEffort
import com.helix.core.storage.HelixStorage

/** Android composition and Goal-time ownership only; the sole model/tool loop lives in Core. */
@Suppress("LongParameterList") // Existing application dependencies are wired once into narrow Core ports.
internal class AgentLoopHost(
    private val storage: HelixStorage,
    providerService: ProviderService,
    contextAssembler: TurnContextAssembler,
    toolExecutor: AgentToolGateway,
    toolMessages: ToolMessageMaterializer,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val liveHandles: TurnExecutionHandles,
    strings: (Int, Array<out Any>) -> String,
    refreshScreen: () -> Unit,
    onTextChanged: (String, String) -> Unit,
    private val persistTerminalInTransaction: (TurnTerminalCheckpoint, ModelStreamTerminal) -> ModelStreamTerminal,
    private val inputDelivery: TurnInputDelivery? = null,
) {
    private val core =
        AgentLoop(
            runtimeAccounting = TurnRuntimeAccounting(storage),
            provenance = RoomRequestProvenance(storage),
            models =
                object : AgentModelAccess {
                    override suspend fun modelProviderFor(
                        providerId: String,
                        model: String,
                    ) = providerService.modelProviderFor(providerId, model)

                    override suspend fun contextSettings(
                        providerId: String,
                        model: String,
                    ) = providerService.contextSettings(providerId, model)
                },
            contextAssembler = contextAssembler,
            toolExecutor = toolExecutor,
            toolMessages = toolMessages,
            goalBudget = GoalModelCallBudget(storage, clock),
            compactionCycles =
                ContextCompactionCycles { session, turn, provider, context, control ->
                    contextCompactionRound(
                        storage,
                        session,
                        turn,
                        control,
                        providerService.contextSettings(provider, context.model),
                        context.messages.lastOrNull()?.text == ContextCommands.COMPACT,
                        providerService.resolveReasoning(provider, context.model, ReasoningEffort.LOW),
                    )
                },
            execution =
                object : LoopExecutionControl {
                    override fun checkActive(turnId: String) {
                        liveHandles.goalTime(turnId)?.checkActive()
                    }

                    override fun isCancelled(turnId: String) = liveHandles.cancelSignal(turnId)?.isCancelled() == true
                },
            notices =
                LoopNotices { notice ->
                    strings(
                        when (notice) {
                            LoopNotice.COMPACTED -> R.string.context_compacted
                            LoopNotice.COMPACTION_UNCHANGED -> R.string.context_compaction_unchanged
                        },
                        emptyArray(),
                    )
                },
            events =
                AgentLoopEvents { event ->
                    when (event) {
                        AgentLoopEvent.Refresh -> refreshScreen()
                        is AgentLoopEvent.TextChanged -> onTextChanged(event.turnId, event.text)
                    }
                },
            clock = clock,
            idGenerator = idGenerator,
        )

    suspend fun runToolLoop(
        sessionId: String,
        coordinator: TurnCoordinator,
        providerId: String,
        retryTurnId: String?,
        control: RunControlConfig,
        entry: AgentLoop.Entry = AgentLoop.Entry.INITIAL,
    ): TurnLoopResult {
        require(coordinator.terminalCheckpoint().sessionId == sessionId) { "LOOP_SESSION_MISMATCH" }
        return core.runToolLoop(
            sessionId,
            RoomTurnJournal(coordinator),
            providerId,
            retryTurnId,
            control,
            entry,
            inputDelivery?.let {
                TurnInputBoundaryAdapter(
                    sessionId,
                    coordinator,
                    it,
                    idGenerator,
                    persistTerminalInTransaction,
                )
            },
        )
    }

    suspend fun runWithGoalTime(
        turnId: String,
        block: suspend () -> TurnLoopResult,
    ): TurnLoopResult {
        val binding = storage.goalTurnBindings.byTurn(turnId) ?: return block()
        val timer = GoalTimeBudget(storage, clock, binding.runId)
        liveHandles.installGoalTime(turnId, timer)
        try {
            return timer.run(
                onExpiry = { requireNotNull(liveHandles.cancelSignal(turnId)) { "TURN_NOT_LIVE: $turnId" }.cancel() },
                completionWins = { it is TurnLoopResult.ParkedForReview },
                block = block,
            )
        } finally {
            liveHandles.clearGoalTime(turnId, timer)
        }
    }
}

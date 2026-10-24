package com.helix.core.agent.runtime

import com.helix.core.agent.AgentLoop
import com.helix.core.agent.AgentLoopAccounting
import com.helix.core.agent.AgentLoopEvents
import com.helix.core.agent.AgentModelAccess
import com.helix.core.agent.AgentToolGateway
import com.helix.core.agent.BufferedModelToolCall
import com.helix.core.agent.ContextCommands
import com.helix.core.agent.ContextCompactionCycles
import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ContextCompactionRound
import com.helix.core.agent.ContextCompactionSource
import com.helix.core.agent.ContextSummaryFormat
import com.helix.core.agent.GoalModelBudget
import com.helix.core.agent.LocalToolCallBatch
import com.helix.core.agent.LoopExecutionControl
import com.helix.core.agent.LoopNotices
import com.helix.core.agent.LoopUsageSnapshot
import com.helix.core.agent.ModelStreamState
import com.helix.core.agent.RequestProvenanceStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SettledBatch
import com.helix.core.agent.SettledCall
import com.helix.core.agent.ToolBatchObserver
import com.helix.core.agent.ToolBatchRequest
import com.helix.core.agent.ToolLoopProgress
import com.helix.core.agent.ToolMessageMaterializer
import com.helix.core.agent.TurnBudgetTracker
import com.helix.core.agent.TurnContextAssembler
import com.helix.core.agent.TurnContextRequest
import com.helix.core.agent.TurnMessageDraft
import com.helix.core.agent.WorkspaceBindingSnapshot
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.Sha256
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderContextSettings
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Actual AgentLoop and coordinator; only provider, host tools and persistence ports are synthetic. */
internal class EngineLoopFixture(
    private val journal: TurnRoundCoordinator,
    private val store: LifecycleTestStore,
    private val modelEvents: (Int) -> List<ModelEvent>,
) {
    val requests = mutableListOf<ModelRequest>()
    val dispatched = mutableListOf<ToolBatchRequest>()
    val completionOrder = mutableListOf<String>()
    var denied = false
    var unknown = false
    private var nextId = 0
    private val provider =
        object : ModelProvider {
            override val descriptor =
                ProviderDescriptor(
                    "provider",
                    "Synthetic",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    "model",
                    NormalizedEndpoint.parse("https://synthetic.invalid/v1"),
                )

            override suspend fun listModels() = error("No catalog access")

            override suspend fun validateConfiguration() = error("No network probes")

            override fun stream(request: ModelRequest) =
                flow {
                    check("manifest:${journal.snapshot().modelCallId}" in store.log)
                    requests += request
                    store.log += "wire:${requests.lastIndex}"
                    modelEvents(requests.lastIndex).forEach { emit(it) }
                }
        }
    private val schemas =
        listOf(
            ModelToolSchema(ToolName("read"), "Read a synthetic value", "{\"type\":\"object\"}"),
        )

    private fun context() = TurnContextRequest("model", store.history.toList(), schemas, 512, ReasoningEffort.OFF)

    private val accounting =
        object : AgentLoopAccounting {
            override suspend fun restore(
                turnId: String,
                providerId: String,
                control: RunControlConfig,
            ): LoopUsageSnapshot? = null

            override suspend fun checkpointModelAdmission(
                turnId: String,
                tracker: TurnBudgetTracker,
            ) {
                store.log += "admitted:${tracker.consumedCalls}"
            }

            override suspend fun checkpointTokens(
                turnId: String,
                tracker: TurnBudgetTracker,
            ) {
                store.log += "tokens:${tracker.consumedTokens}"
            }

            override suspend fun checkpointToolRound(
                turnId: String,
                admittedToolRounds: Int,
            ) {
                store.log += "round:$admittedToolRounds"
            }
        }
    private val assembler =
        object : TurnContextAssembler {
            override suspend fun build(
                sessionId: String,
                turnId: String,
                retryTurnId: String?,
                control: RunControlConfig,
            ) = context()

            override suspend fun rebuild(
                sessionId: String,
                turnId: String,
                retryTurnId: String?,
                control: RunControlConfig,
                previous: TurnContextRequest,
            ) = context()

            override suspend fun buildBackfill(
                sessionId: String,
                turnId: String,
                control: RunControlConfig,
            ) = context()
        }
    private val gateway =
        object : AgentToolGateway {
            override fun loopProgress(turnId: String) = ToolLoopProgress.Decision.CONTINUE

            override fun resetLoopProgress(turnId: String) = Unit

            override fun prepareModelCalls(
                calls: List<BufferedModelToolCall>,
                directory: FileScopePath?,
                exposedTools: List<ModelToolSchema>,
            ) = calls

            override suspend fun executeBatch(
                request: ToolBatchRequest,
                observer: ToolBatchObserver,
            ): SettledBatch {
                check(journal.snapshot().modelCallClosed && "assistant-committed" in store.log)
                check(request.sessionId == "session" && request.turnId == journal.id)
                dispatched += request
                val calls =
                    request.calls.map { call ->
                        val outcome =
                            when {
                                unknown -> {
                                    ToolDispatchOutcome.ExecutionFailed(
                                        DispatchOutcomeCode.TIMEOUT,
                                        "Receipt lost",
                                        requiresReview = true,
                                    )
                                }

                                denied -> {
                                    ToolDispatchOutcome.Denied(
                                        DispatchOutcomeCode.POLICY_DENIED,
                                        "Synthetic authorization refusal",
                                    )
                                }

                                else -> {
                                    ToolDispatchOutcome.Succeeded(
                                        BoundToolResult("7", Sha256("a".repeat(64)), false, 1),
                                    )
                                }
                            }
                        SettledCall(call.callId, call.name, outcome)
                    }
                calls.reversed().forEach {
                    completionOrder += it.callId
                    observer.settled(it.callId, unknown)
                }
                return SettledBatch(calls, if (unknown) calls.map { it.callId } else emptyList())
            }
        }
    private val messages =
        object : ToolMessageMaterializer {
            override fun assistantToolStepJson(batch: LocalToolCallBatch) =
                JsonArray(
                    batch.calls.map { call ->
                        buildJsonObject {
                            put("id", batch.wireId(call.callId))
                            put("name", call.name)
                            put("arguments", call.arguments)
                        }
                    },
                ).toString()

            override fun toolResultDraft(settled: SettledCall) =
                TurnMessageDraft(
                    ModelRole.TOOL,
                    "TOOL_RESULT",
                    buildJsonObject {
                        put("id", settled.callId)
                        put("name", settled.toolName)
                        val value =
                            when (settled.outcome) {
                                is ToolDispatchOutcome.Denied -> "POLICY_DENIED"
                                is ToolDispatchOutcome.Succeeded -> "7"
                                else -> error("Unknown effects must never be backfilled")
                            }
                        put("value", value)
                    }.toString(),
                )

            override fun progressDraft(decision: ToolLoopProgress.Decision): TurnMessageDraft? = null
        }

    fun loop() =
        AgentLoop(
            accounting,
            object : RequestProvenanceStore {
                override suspend fun inputIds(
                    turnId: String,
                    messageIds: Set<String>,
                ) = emptyList<String>()

                override suspend fun recordWorkspace(
                    modelCallId: String,
                    binding: WorkspaceBindingSnapshot,
                ) = error("No workspace in synthetic task")
            },
            object : AgentModelAccess {
                override suspend fun modelProviderFor(
                    providerId: String,
                    model: String,
                ) = provider

                override suspend fun contextSettings(
                    providerId: String,
                    model: String,
                ) = ProviderContextSettings()
            },
            assembler,
            gateway,
            messages,
            object : GoalModelBudget {
                override suspend fun prepare(
                    turnId: String,
                    callId: String,
                    request: ModelRequest,
                ) = request

                override suspend fun finish(
                    turnId: String,
                    callId: String,
                    request: ModelRequest,
                    stream: ModelStreamState,
                ) = Unit

                override suspend fun canContinue(turnId: String) = true
            },
            ContextCompactionCycles { _, _, _, context, config ->
                ContextCompactionRound(
                    object : ContextCompactionSource {
                        override suspend fun inputFloor(model: String) = 0L

                        override suspend fun plan(
                            request: TurnContextRequest,
                            control: RunControlConfig,
                            settings: ProviderContextSettings,
                            force: Boolean,
                            inputScale: Double,
                        ): ContextCompactionPlan? {
                            check(!force)
                            return null
                        }
                    },
                    ContextSummaryFormat("Summarize.", "Preserve facts."),
                    config,
                    ProviderContextSettings(),
                    context.messages.last().text == ContextCommands.COMPACT,
                )
            },
            object : LoopExecutionControl {
                override fun checkActive(turnId: String) = Unit

                override fun isCancelled(turnId: String) = false
            },
            LoopNotices { it.name },
            AgentLoopEvents { store.log += "event:${it::class.simpleName}" },
            object : Clock {
                override fun now() = Instant.ofEpochMilli(1000)
            },
            { "engine-loop-${++nextId}" },
        )
}

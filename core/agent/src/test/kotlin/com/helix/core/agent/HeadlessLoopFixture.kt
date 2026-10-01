package com.helix.core.agent

import com.helix.core.model.AgentMode
import com.helix.core.model.BoundToolResult
import com.helix.core.model.Clock
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.Sha256
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.ToolName
import com.helix.core.model.TurnBudgets
import com.helix.core.workspace.FileScopePath
import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderContextSettings
import com.helix.provider.api.ProviderDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** No App, Room, Android runtime, HTTP transport or credentials are on this fixture's classpath. */
internal class HeadlessLoopFixture(
    private val modelEvents: (Int) -> List<ModelEvent>,
) {
    val journal = HeadlessTurnJournal()
    val requests = mutableListOf<ModelRequest>()
    val events = mutableListOf<AgentLoopEvent>()
    val dispatched = mutableListOf<ToolBatchRequest>()
    val control = RunControlConfig(AgentMode.ACT, true, TurnBudgets(8, 9, 128_000, 4096, 160_000))
    var cancelled = false
    var cancelAfterEvent: Int? = null
    var unknown = false
    var reverseResults = false
    var stopProgress = false
    var failEvents = false
    var modelCallCount = 0
    var tokenCount = 0L
    var restored: LoopUsageSnapshot? = null
    var source: ContextCompactionSource? = null
    private var ids = 0
    private val settings = ProviderContextSettings()
    private val summaries = ContextSummaryFormat("Summarize.", "Preserve facts.")
    private val tools = listOf(ModelToolSchema(ToolName("read"), "Read a fixture value", "{\"type\":\"object\"}"))

    private fun context() = TurnContextRequest("fixture", journal.history.toList(), tools, 512, ReasoningEffort.OFF)

    private val provider =
        object : ModelProvider {
            override val descriptor: ProviderDescriptor
                get() = error("Unexpected configuration read")

            override suspend fun listModels() = error("No catalog network access is permitted")

            override suspend fun validateConfiguration() = error("No connection probe is permitted")

            override fun stream(request: ModelRequest): Flow<ModelEvent> =
                flow {
                    val call = journal.snapshot().modelCallId
                    check("manifest:$call" in journal.log) { "wire request preceded durable manifest" }
                    requests += request
                    val index = requests.lastIndex
                    journal.log += "wire:$index"
                    modelEvents(index).forEachIndexed { ordinal, event ->
                        emit(event)
                        if (cancelAfterEvent == ordinal) cancelled = true
                    }
                }
        }

    private val accounting =
        object : AgentLoopAccounting {
            override suspend fun restore(
                turnId: String,
                providerId: String,
                control: RunControlConfig,
            ) = restored

            override suspend fun checkpointModelAdmission(
                turnId: String,
                tracker: TurnBudgetTracker,
            ) {
                modelCallCount = tracker.consumedCalls
            }

            override suspend fun checkpointTokens(
                turnId: String,
                tracker: TurnBudgetTracker,
            ) {
                tokenCount = tracker.consumedTokens
            }

            override suspend fun checkpointToolRound(
                turnId: String,
                admittedToolRounds: Int,
            ) {
                journal.log += "round:$admittedToolRounds"
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
            override fun loopProgress(turnId: String) =
                if (stopProgress) ToolLoopProgress.Decision.STOP else ToolLoopProgress.Decision.CONTINUE

            override fun resetLoopProgress(turnId: String) {
                stopProgress = false
            }

            override fun prepareModelCalls(
                calls: List<BufferedModelToolCall>,
                directory: FileScopePath?,
                exposedTools: List<ModelToolSchema>,
            ) = calls

            override suspend fun executeBatch(
                request: ToolBatchRequest,
                observer: ToolBatchObserver,
            ): SettledBatch {
                check(journal.snapshot().modelCallClosed) { "execution preceded assistant commit" }
                dispatched += request
                val outcomes =
                    request.calls.map { call ->
                        val outcome =
                            if (unknown) {
                                ToolDispatchOutcome.ExecutionFailed(
                                    DispatchOutcomeCode.TIMEOUT,
                                    "Uncertain fixture",
                                    requiresReview = true,
                                )
                            } else {
                                ToolDispatchOutcome.Succeeded(BoundToolResult("7", Sha256("a".repeat(64)), false, 1))
                            }
                        observer.settled(call.callId, unknown)
                        SettledCall(call.callId, call.name, outcome)
                    }
                return SettledBatch(
                    if (reverseResults) outcomes.reversed() else outcomes,
                    if (unknown) request.calls.map { it.callId } else emptyList(),
                )
            }
        }
    private val materializer =
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
                        put("value", "7")
                    }.toString(),
                )

            override fun progressDraft(decision: ToolLoopProgress.Decision): TurnMessageDraft? =
                if (decision == ToolLoopProgress.Decision.STOP) {
                    TurnMessageDraft(ModelRole.SYSTEM, "loop_exhausted", "Stop without progress.")
                } else {
                    null
                }
        }

    fun loop(): AgentLoop =
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
                ) {
                    journal.log += "workspace:$modelCallId"
                }
            },
            object : AgentModelAccess {
                override suspend fun modelProviderFor(
                    providerId: String,
                    model: String,
                ) = provider

                override suspend fun contextSettings(
                    providerId: String,
                    model: String,
                ) = settings
            },
            assembler,
            gateway,
            materializer,
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
                ) {
                    journal.log += "usage:$callId"
                }

                override suspend fun canContinue(turnId: String) = true
            },
            ContextCompactionCycles { _, _, _, context, config ->
                ContextCompactionRound(
                    source ?: object : ContextCompactionSource {
                        override suspend fun inputFloor(model: String) = 0L

                        override suspend fun plan(
                            request: TurnContextRequest,
                            control: RunControlConfig,
                            settings: ProviderContextSettings,
                            force: Boolean,
                            inputScale: Double,
                        ): ContextCompactionPlan? {
                            // No old history exists in ordinary short fixture tasks.
                            check(!force) { "unexpected compaction" }
                            return null
                        }
                    },
                    summaries,
                    config,
                    settings,
                    context.messages.last().text == ContextCommands.COMPACT,
                )
            },
            object : LoopExecutionControl {
                override fun checkActive(turnId: String) = Unit

                override fun isCancelled(turnId: String) = cancelled
            },
            LoopNotices { it.name },
            AgentLoopEvents {
                check(!failEvents) { "presentation failure" }
                events += it
            },
            object : Clock {
                override fun now() = Instant.ofEpochMilli(1000)
            },
            { "local-${++ids}" },
        )
}

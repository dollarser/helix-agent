package com.helix.core.agent

import com.helix.provider.api.ModelProvider
import com.helix.provider.api.ProviderContextSettings

/** Read projection of the existing runtime ledger, not a second persisted budget or owner. */
data class LoopUsageSnapshot(
    val modelId: String,
    val consumedModelCalls: Int,
    val consumedTokens: Long,
    val admittedToolRounds: Int,
)

interface AgentLoopAccounting {
    suspend fun restore(
        turnId: String,
        providerId: String,
        control: RunControlConfig,
    ): LoopUsageSnapshot?

    suspend fun checkpointModelAdmission(
        turnId: String,
        tracker: TurnBudgetTracker,
    )

    suspend fun checkpointTokens(
        turnId: String,
        tracker: TurnBudgetTracker,
    )

    suspend fun checkpointToolRound(
        turnId: String,
        admittedToolRounds: Int,
    )
}

interface RequestProvenanceStore {
    suspend fun inputIds(
        turnId: String,
        messageIds: Set<String>,
    ): List<String>

    suspend fun recordWorkspace(
        modelCallId: String,
        binding: WorkspaceBindingSnapshot,
    )
}

/** Reuses the existing protocol-neutral ModelProvider; no new vendor or HTTP interface. */
interface AgentModelAccess {
    suspend fun modelProviderFor(
        providerId: String,
        model: String,
    ): ModelProvider

    suspend fun contextSettings(
        providerId: String,
        model: String,
    ): ProviderContextSettings
}

/** Process-local control only. It never supplies durable outcome or releases an execution owner. */
interface LoopExecutionControl {
    fun checkActive(turnId: String)

    fun isCancelled(turnId: String): Boolean
}

sealed interface AgentLoopEvent {
    data object Refresh : AgentLoopEvent

    data class TextChanged(
        val turnId: String,
        val text: String,
    ) : AgentLoopEvent
}

fun interface AgentLoopEvents {
    fun emit(event: AgentLoopEvent)
}

enum class LoopNotice { COMPACTED, COMPACTION_UNCHANGED }

/** Typed presentation content, resolved at use time; no Android resource identifiers or authority. */
fun interface LoopNotices {
    fun text(notice: LoopNotice): String
}

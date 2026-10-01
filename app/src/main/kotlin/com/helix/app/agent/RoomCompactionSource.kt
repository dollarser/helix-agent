package com.helix.app.agent

import com.helix.core.agent.ContextCompactionPlan
import com.helix.core.agent.ContextCompactionRound
import com.helix.core.agent.ContextCompactionSource
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnContextRequest
import com.helix.core.model.ReasoningEffort
import com.helix.core.storage.HelixStorage
import com.helix.provider.api.ProviderContextSettings

/** Host I/O stage over the existing history and pressure facts; no separate selection algorithm. */
internal class RoomCompactionSource(
    private val storage: HelixStorage,
    private val sessionId: String,
    private val turnId: String,
) : ContextCompactionSource {
    override suspend fun inputFloor(model: String): Long = ContextPressure.inputFloor(storage, sessionId, turnId, model)

    override suspend fun plan(
        request: TurnContextRequest,
        control: RunControlConfig,
        settings: ProviderContextSettings,
        force: Boolean,
        inputScale: Double,
    ): ContextCompactionPlan? =
        ContextCompaction.plan(storage, sessionId, request, control, settings, force, turnId, inputScale)
}

/** Constructs the same Core cycle for production and real Room fixtures. */
internal fun contextCompactionRound(
    storage: HelixStorage,
    sessionId: String,
    turnId: String,
    control: RunControlConfig,
    settings: ProviderContextSettings,
    manual: Boolean,
    summaryReasoning: ReasoningEffort = ReasoningEffort.OFF,
): ContextCompactionRound =
    ContextCompactionRound(
        RoomCompactionSource(storage, sessionId, turnId),
        ContextCompaction.summaries,
        control,
        settings,
        manual,
        summaryReasoning,
    )

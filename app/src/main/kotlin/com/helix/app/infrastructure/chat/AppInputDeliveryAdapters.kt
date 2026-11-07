package com.helix.app.infrastructure.chat

import com.helix.app.application.agent.TurnSubmissionCommandHandler
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.TurnSubmissionResult
import com.helix.core.agent.runtime.TurnEngine
import com.helix.core.agent.turnIdOrNull
import com.helix.core.model.Clock
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.conversation.application.input.InputDeliveryProvider
import com.helix.feature.conversation.application.input.InputDeliveryRuntime
import com.helix.feature.conversation.application.input.PreparedSessionInput

internal class AppInputDeliveryProvider(
    private val providers: ProviderService,
    private val captureSnapshot: suspend (String, String?) -> String,
) : InputDeliveryProvider {
    override fun selectable(providerId: String): Boolean = providers.chatSelectable(providerId)

    override suspend fun defaultModel(providerId: String): String = providers.storedConfig(providerId).model

    override suspend fun supportsVision(
        providerId: String,
        modelId: String?,
    ): Boolean = providers.capabilitiesFor(providerId, modelId)?.vision == true

    override suspend fun supportsTools(
        providerId: String,
        modelId: String?,
    ): Boolean = providers.capabilitiesFor(providerId, modelId)?.toolCalls == true

    override suspend fun snapshot(
        providerId: String,
        modelId: String?,
    ): String = captureSnapshot(providerId, modelId)
}

internal class AppInputDeliveryRuntime(
    private val engine: TurnEngine,
    private val controls: SessionRunControlStore,
    private val commands: TurnSubmissionCommandHandler,
    private val clock: Clock,
    private val projectResult: (String, TurnSubmissionResult) -> String?,
) : InputDeliveryRuntime {
    override fun hasLiveTurn(sessionId: String): Boolean = engine.liveExecution.hasActive(sessionId)

    override fun selectedControl(sessionId: String): RunControlConfig =
        controls.ensure(sessionId, clock.now().toEpochMilli())

    override fun steerControl(turnId: String?): RunControlConfig? =
        turnId?.let { engine.liveExecution.byTurn(it)?.control }

    override fun queueStillConsumable(
        key: InputDeliveryKey,
        requireHead: Boolean,
    ): Boolean = commands.queueStillConsumable(key.inputId, key.sessionId, key.revision, requireHead)

    override suspend fun deliverQueued(
        key: InputDeliveryKey,
        prepared: PreparedSessionInput,
        control: RunControlConfig,
        requireHead: Boolean,
    ): String? {
        val result =
            commands.deliverQueued(
                key.inputId,
                key.revision,
                prepared.content,
                prepared.attachments,
                control,
                requireHead,
            )
        projectResult(key.sessionId, result)
        return result.turnIdOrNull()
    }
}

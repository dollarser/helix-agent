package com.helix.app.chat

import android.util.Log
import com.helix.app.engine.TurnEngine
import com.helix.app.provider.ProviderService
import com.helix.app.runcontrol.SessionRunControlStore
import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import kotlinx.coroutines.CancellationException

/**
 * Revalidates and delivers already-accepted Session inputs.
 *
 * Acceptance/disclosure remain Chat application concerns; this component owns the runtime-facing
 * delivery path shared by queued user input and in-Turn Steer. Room is authoritative, and every
 * start rechecks provider/model/control/attachment facts before creating a Turn.
 */
@Suppress("LongParameterList") // Application wiring; dependencies are narrow runtime/validation ports.
internal class SessionInputDeliveryCoordinator(
    private val storage: HelixStorage,
    private val providerService: ProviderService,
    private val sessionRunControls: SessionRunControlStore,
    private val turnEngine: TurnEngine,
    private val attachmentStaging: AttachmentStagingSupport,
    private val credentialScan: (String) -> String?,
    private val providerSnapshot: suspend (String, String?) -> String,
    private val clock: Clock,
    private val turnGate: Any,
    private val launchQueuedTurn: suspend (
        SessionInputRecord,
        String,
        List<MessageAttachmentRepository.Binding>,
        RunControlConfig,
        Boolean,
    ) -> String?,
) {
    fun queueStillConsumable(
        input: SessionInputRecord,
        requireHead: Boolean,
    ): Boolean {
        val current = storage.sessionInputs.get(input.inputId) ?: return false
        val headMatches = !requireHead || storage.sessionInputs.headQueue(input.sessionId)?.inputId == input.inputId
        val unsettled =
            storage.turns.listBySession(input.sessionId).any { turn ->
                val state =
                    com.helix.core.model.TurnState
                        .valueOf(turn.state)
                !state.isTerminal && state != com.helix.core.model.TurnState.NEEDS_REVIEW
            }
        val unchangedPending = current.state == SessionInputState.PENDING && current.revision == input.revision
        return unchangedPending && headMatches && !unsettled
    }

    suspend fun consumeQueue(
        input: SessionInputRecord,
        requireHead: Boolean = true,
    ): String? {
        val eligible =
            synchronized(turnGate) {
                !turnEngine.liveExecution.hasActive(input.sessionId) && queueStillConsumable(input, requireHead)
            }
        val prepared = if (eligible) revalidate(input) else null
        return if (prepared == null) {
            null
        } else {
            val control = controlFor(input)
            val turnId = launchQueuedTurn(input, prepared.first, prepared.second, control, requireHead)
            if (turnId == null) markAdmissionFailureIfStillIdle(input)
            turnId
        }
    }

    fun controlFor(input: SessionInputRecord): RunControlConfig =
        if (input.delivery == SessionInputDelivery.STEER) {
            steerControl(input.expectedTurnId)
                ?: sessionRunControls
                    .ensure(input.sessionId, clock.now().toEpochMilli())
                    .copy(mode = AgentMode.valueOf(input.configuration.mode))
        } else {
            sessionRunControls
                .ensure(input.sessionId, clock.now().toEpochMilli())
                .copy(mode = AgentMode.valueOf(input.configuration.mode))
        }

    fun steerControl(expectedTurnId: String?): RunControlConfig? =
        expectedTurnId?.let { turnEngine.liveExecution.byTurn(it)?.control }

    @Suppress("TooGenericExceptionCaught") // Durable accepted input is parked, never silently discarded.
    suspend fun revalidate(
        input: SessionInputRecord,
    ): Pair<String, List<MessageAttachmentRepository.Binding>>? =
        try {
            val session = storage.sessions.resolve(input.sessionId)
            val provider = input.configuration.providerId
            check(providerService.chatSelectable(provider) && providerService.isCleartextPermitted(provider))
            val facts = providerSnapshot(provider, session.modelId)
            val control = controlFor(input)
            val selected = sessionRunControls.ensure(input.sessionId, clock.now().toEpochMilli())
            if (input.delivery == SessionInputDelivery.STEER) {
                val originalCall = storage.modelCalls.listByTurn(requireNotNull(input.expectedTurnId)).firstOrNull()
                check(originalCall?.providerSnapshot == facts) { "INPUT_CONFIGURATION_CHANGED" }
            }
            val valid =
                session.providerId == provider &&
                    (session.modelId ?: providerService.storedConfig(provider).model) == input.configuration.modelId &&
                    SessionInputBinding.matchesConfiguration(
                        input,
                        facts,
                        control,
                        if (input.delivery == SessionInputDelivery.STEER) {
                            control.mode.name
                        } else {
                            selected.mode.name
                        },
                    )
            check(valid) { "INPUT_CONFIGURATION_CHANGED" }
            val hasImages =
                input.attachments.any {
                    storage.artifacts.resolve(it.artifactId).mediaType in VisionLimits.NORMALIZED_MEDIA_TYPES
                }
            check(!hasImages || providerService.capabilitiesFor(provider, session.modelId)?.vision == true)
            storage.sessionInputs.readReference(input)
            SessionInputAttachments(storage, attachmentStaging, credentialScan).materialize(input)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            synchronized(turnGate) {
                storage.sessionInputs.markNeedsAttention(
                    input.inputId,
                    input.revision,
                    "INPUT_REVALIDATION_FAILED",
                    clock.now().toEpochMilli(),
                )
            }
            Log.w(TAG, "Queued input revalidation failed", error)
            null
        }

    suspend fun prepareSteering(
        sessionId: String,
        turnId: String,
    ): com.helix.app.agent.TurnSteeringDraft? {
        val input =
            storage.sessionInputs.headSteer(sessionId, turnId)?.takeIf { it.state == SessionInputState.PENDING }
                ?: return null
        return revalidate(input)?.let { (content, bindings) ->
            com.helix.app.agent
                .TurnSteeringDraft(
                    input,
                    content,
                    bindings,
                    listOfNotNull(storage.sessionInputs.readReference(input)),
                )
        }
    }

    private fun markAdmissionFailureIfStillIdle(input: SessionInputRecord) {
        synchronized(turnGate) {
            val current = storage.sessionInputs.get(input.inputId)
            val idle =
                !turnEngine.liveExecution.hasActive(input.sessionId) &&
                    storage.turns.listBySession(input.sessionId).all {
                        com.helix.core.model.TurnState
                            .valueOf(it.state)
                            .isTerminal
                    }
            val pending = current?.state == SessionInputState.PENDING
            val unchanged = current?.revision == input.revision
            if (pending && unchanged && idle) {
                storage.sessionInputs.markNeedsAttention(
                    input.inputId,
                    input.revision,
                    "INPUT_ADMISSION_FAILED",
                    clock.now().toEpochMilli(),
                )
            }
        }
    }

    private companion object {
        const val TAG = "SessionInputDelivery"
    }
}

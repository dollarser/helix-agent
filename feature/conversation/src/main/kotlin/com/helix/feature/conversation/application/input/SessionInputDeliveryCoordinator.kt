package com.helix.feature.conversation.application.input

import com.helix.core.agent.ConversationReferenceIntent
import com.helix.core.agent.RunControlConfig
import com.helix.core.agent.SessionInputConfiguration
import com.helix.core.agent.turn.TurnSteeringConfiguration
import com.helix.core.agent.turn.TurnSteeringInput
import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.input.InputSkillActivation
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery
import kotlinx.coroutines.CancellationException

/**
 * Rechecks accepted identity, provider/configuration, tool authority and bound bytes before delivery.
 * Explicit storage, runtime, provider and authorization ports remain in this one delivery coordinator.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class SessionInputDeliveryCoordinator(
    private val store: SessionInputDeliveryStore,
    private val provider: InputDeliveryProvider,
    private val runtime: InputDeliveryRuntime,
    private val materialize: (AcceptedInputSnapshot) -> PreparedSessionInput,
    private val clock: Clock,
    private val turnGate: Any,
    private val reportFailure: (Exception) -> Unit,
    private val knowledgeReferencesValid: suspend (List<KnowledgeReference>) -> Boolean,
    private val referenceToolsAvailable: (String, Boolean, Boolean) -> Boolean,
    private val skillActivationsValid: (String, List<InputSkillActivation>) -> Boolean,
) {
    fun queueStillConsumable(
        key: InputDeliveryKey,
        requireHead: Boolean,
    ): Boolean = runtime.queueStillConsumable(key, requireHead)

    suspend fun consumeQueue(
        key: InputDeliveryKey,
        requireHead: Boolean = true,
    ): String? {
        val eligible =
            synchronized(turnGate) {
                !runtime.hasLiveTurn(key.sessionId) && queueStillConsumable(key, requireHead)
            }
        val input = if (eligible) load(key, true) else null
        val prepared = input?.let { revalidate(it) } ?: return null
        val turnId = runtime.deliverQueued(key, prepared, controlFor(input), requireHead)
        if (turnId == null) {
            synchronized(turnGate) {
                if (!runtime.hasLiveTurn(key.sessionId)) store.parkAdmissionFailureIfIdle(key, now())
            }
        }
        return turnId
    }

    fun steerControl(turnId: String?): RunControlConfig? = runtime.steerControl(turnId)

    private fun controlFor(input: AcceptedInputSnapshot): RunControlConfig =
        if (input.delivery == SessionInputDelivery.STEER) {
            steerControl(input.expectedTurnId) ?: selectedControl(input)
        } else {
            selectedControl(input)
        }

    private fun selectedControl(input: AcceptedInputSnapshot) =
        runtime.selectedControl(input.key.sessionId).copy(mode = AgentMode.valueOf(input.configuration.mode))

    suspend fun revalidate(
        key: InputDeliveryKey,
        parkFailure: Boolean = true,
    ): PreparedSessionInput? = load(key, parkFailure)?.let { revalidate(it, parkFailure) }

    /**
     * Queue-to-Steer uses the explicit original target without rewriting the accepted input.
     * Each failed identity/target lookup stops before revalidating or mutating the queued input.
     */
    @Suppress("ReturnCount")
    suspend fun revalidateForSteering(
        key: InputDeliveryKey,
        expectedTurnId: String,
    ): PreparedSessionInput? {
        val input = load(key, false) ?: return null
        if (input.delivery != SessionInputDelivery.QUEUE) return null
        val original = store.steerProviderSnapshot(key.sessionId, expectedTurnId) ?: return null
        return revalidate(
            input.copy(
                delivery = SessionInputDelivery.STEER,
                expectedTurnId = expectedTurnId,
                originalProviderSnapshot = original,
            ),
            parkFailure = false,
        )
    }

    @Suppress("TooGenericExceptionCaught") // Corrupt accepted bytes are parked at this exact revision, never discarded.
    private fun load(key: InputDeliveryKey, parkFailure: Boolean): AcceptedInputSnapshot? =
        try {
            store.load(key)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failed(key, error, parkFailure)
            null
        }

    // All materialization/provider failures park this exact intent; cancellation propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun revalidate(
        input: AcceptedInputSnapshot,
        parkFailure: Boolean = true,
    ): PreparedSessionInput? =
        try {
            check(skillActivationsValid(input.key.sessionId, input.skillActivations)) { "SKILL_ACTIVATION_UNAVAILABLE" }
            val session = requireNotNull(store.selection(input.key.sessionId))
            val providerId = input.configuration.providerId
            check(provider.selectable(providerId))
            val facts = provider.snapshot(providerId, session.modelId)
            val control = controlFor(input)
            val selected = runtime.selectedControl(input.key.sessionId)
            if (input.delivery == SessionInputDelivery.STEER) {
                check(input.originalProviderSnapshot == facts) { "INPUT_CONFIGURATION_CHANGED" }
            }
            check(
                session.providerId == providerId &&
                    (session.modelId ?: provider.defaultModel(providerId)) == input.configuration.modelId &&
                    SessionInputConfiguration.matches(
                        input.configuration,
                        facts,
                        control,
                        if (input.delivery == SessionInputDelivery.STEER) control.mode.name else selected.mode.name,
                    ),
            ) { "INPUT_CONFIGURATION_CHANGED" }
            check(!input.hasImages || provider.supportsVision(providerId, session.modelId))
            validateReferenceTools(input, control, session.modelId)
            check(knowledgeReferencesValid(input.knowledgeReferences)) { "KNOWLEDGE_REFERENCE_CHANGED" }
            materialize(input)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            failed(input.key, error, parkFailure)
            null
        }

    private fun failed(
        key: InputDeliveryKey,
        error: Exception,
        parkFailure: Boolean,
    ) {
        if (parkFailure) {
            synchronized(turnGate) {
                val reason =
                    error.message?.takeIf { it in setOf("KNOWLEDGE_REFERENCE_CHANGED", "REFERENCE_TOOLS_REQUIRED") }
                        ?: "INPUT_REVALIDATION_FAILED"
                store.park(key, reason, now())
            }
        }
        reportFailure(error)
    }

    private suspend fun validateReferenceTools(
        input: AcceptedInputSnapshot,
        control: RunControlConfig,
        modelId: String?,
    ) {
        val conversation = input.reference != null
        val knowledge = input.knowledgeReferences.isNotEmpty()
        if (!conversation && !knowledge) return
        val allowed = control.mode != AgentMode.CHAT || control.chatToolsEnabled
        check(
            allowed && provider.supportsTools(input.configuration.providerId, modelId) &&
                referenceToolsAvailable(input.key.sessionId, conversation, knowledge),
        ) { "REFERENCE_TOOLS_REQUIRED" }
    }

    // No steering payload exists until its key, accepted snapshot and verified bytes all exist.
    @Suppress("ReturnCount")
    suspend fun prepareSteering(
        sessionId: String,
        turnId: String,
    ): TurnSteeringInput? {
        val key = store.headSteer(sessionId, turnId) ?: return null
        val input = load(key, true) ?: return null
        val prepared = revalidate(input) ?: return null
        return TurnSteeringInput(
            key.inputId,
            key.revision,
            input.configuration.let { TurnSteeringConfiguration(it.providerId, it.modelId, it.mode, it.fingerprint) },
            prepared.content,
            prepared.attachments,
            listOfNotNull(
                input.reference?.let {
                    ConversationReferenceIntent(
                        it.sourceSessionId,
                        it.sourceSessionTitle,
                        it.selectionKind.name,
                        it.sourceMessageIds,
                        it.content,
                        it.contentSha256,
                    )
                },
            ),
        )
    }

    private fun now() = clock.now().toEpochMilli()
}

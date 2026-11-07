package com.helix.app.infrastructure.chat

import com.helix.app.infrastructure.chat.AttachmentStagingSupport
import com.helix.app.infrastructure.chat.SessionInputAttachments
import com.helix.core.agent.turn.TurnInputAttachment
import com.helix.core.model.TurnState
import com.helix.core.model.VisionLimits
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.application.input.AcceptedInputSnapshot
import com.helix.feature.conversation.application.input.InputDeliveryKey
import com.helix.feature.conversation.application.input.InputSessionSelection
import com.helix.feature.conversation.application.input.PreparedSessionInput
import com.helix.feature.conversation.application.input.SessionInputDeliveryStore

/** Maps bounded accepted content to use-case facts. Persisted content handles never cross this boundary. */
internal class RoomSessionInputDeliveryStore(
    private val storage: HelixStorage,
) : SessionInputDeliveryStore {
    override fun load(key: InputDeliveryKey): AcceptedInputSnapshot? =
        read {
            val record = exact(key) ?: return@read null
            AcceptedInputSnapshot(
                key,
                record.delivery,
                record.expectedTurnId,
                record.configuration,
                record.attachments,
                record.attachments.any {
                    storage.artifacts.resolve(it.artifactId).mediaType in
                        VisionLimits.NORMALIZED_MEDIA_TYPES
                },
                storage.sessionInputs.readReference(record),
                record.skillActivations,
                record.knowledgeReferences,
                record.expectedTurnId?.let {
                    storage.modelCalls
                        .listByTurn(it)
                        .firstOrNull()
                        ?.providerSnapshot
                },
            )
        }

    override fun selection(sessionId: String): InputSessionSelection? =
        storage.sessions
            .find(sessionId)
            ?.takeIf {
                it.archivedAt == null
            }?.let { InputSessionSelection(it.providerId, it.modelId) }

    override fun headSteer(
        sessionId: String,
        turnId: String,
    ): InputDeliveryKey? =
        storage.sessionInputs
            .headSteer(
                sessionId,
                turnId,
            )?.takeIf { it.state == SessionInputState.PENDING }
            ?.deliveryKey()

    override fun steerProviderSnapshot(
        sessionId: String,
        turnId: String,
    ): String? =
        read {
            val turn = storage.turns.find(turnId) ?: return@read null
            if (turn.sessionId != sessionId || TurnState.valueOf(turn.state).isTerminal ||
                turn.state in setOf("CANCELLING", "NEEDS_REVIEW", "INTERRUPTED")
            ) {
                return@read null
            }
            storage.modelCalls
                .listByTurn(turnId)
                .firstOrNull()
                ?.providerSnapshot
        }

    override fun park(
        key: InputDeliveryKey,
        reason: String,
        at: Long,
    ) = read {
        if (exact(key) != null) storage.sessionInputs.markNeedsAttention(key.inputId, key.revision, reason, at)
        Unit
    }

    override fun parkAdmissionFailureIfIdle(
        key: InputDeliveryKey,
        at: Long,
    ) = read {
        val current = exact(key)
        if (current?.state == SessionInputState.PENDING &&
            storage.turns.listBySession(key.sessionId).all { TurnState.valueOf(it.state).isTerminal }
        ) {
            storage.sessionInputs.markNeedsAttention(key.inputId, key.revision, "INPUT_ADMISSION_FAILED", at)
        }
        Unit
    }

    fun materialize(
        input: AcceptedInputSnapshot,
        staging: AttachmentStagingSupport,
        scan: (String) -> String?,
    ): PreparedSessionInput {
        val record = requireNotNull(exact(input.key)) { "INPUT_REVISION_CHANGED" }
        val materialized = SessionInputAttachments(storage, staging, scan).materialize(record)
        return PreparedSessionInput(
            materialized.first,
            materialized.second.map {
                TurnInputAttachment(it.artifactId, it.purpose, it.boundSha256)
            },
        )
    }

    private fun exact(key: InputDeliveryKey): SessionInputRecord? =
        storage.sessionInputs.get(key.inputId)?.takeIf {
            it.sessionId == key.sessionId && it.revision == key.revision &&
                it.state in setOf(SessionInputState.PENDING, SessionInputState.NEEDS_ATTENTION) &&
                selection(key.sessionId) != null
        }

    private fun <T> read(block: () -> T): T {
        var value: T? = null
        storage.withTransaction { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

internal fun SessionInputRecord.deliveryKey() = InputDeliveryKey(inputId, sessionId, revision)

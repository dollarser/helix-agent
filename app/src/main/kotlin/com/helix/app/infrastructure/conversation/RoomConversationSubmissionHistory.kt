package com.helix.app.infrastructure.conversation

import com.helix.app.application.agent.TurnInputFingerprint
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationSubmissionHistory
import com.helix.feature.conversation.application.attachment.AttachmentContext
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome

internal class RoomConversationSubmissionHistory(
    private val storage: HelixStorage,
) : ConversationSubmissionHistory {
    override fun completed(request: ChatSubmission): ChatSubmissionOutcome? {
        var result: ChatSubmissionOutcome? = null
        storage.withTransaction {
            result =
                storage.turns
                    .resolveByClientRequestId(
                        request.clientRequestId,
                    )?.let { completedDirectSubmission(request, it) }
        }
        return result
    }

    override fun acceptedTurnId(
        requestId: String,
        sessionId: String,
    ): String? =
        storage.turns
            .resolveByClientRequestId(requestId)
            ?.takeIf { it.sessionId == sessionId }
            ?.id

    private fun completedDirectSubmission(
        request: ChatSubmission,
        turn: com.helix.core.storage.entity.TurnEntity,
    ): ChatSubmissionOutcome {
        val matches = matchesPersistedSubmission(request, turn)
        return if (matches) {
            ChatSubmissionOutcome.Accepted(turn.id)
        } else {
            ChatSubmissionOutcome.Rejected("REQUEST_ID_ALREADY_USED")
        }
    }

    /** Receipt recovery uses accepted history, never an optional input cache. */
    @Suppress("ReturnCount") // Each mismatch refuses reuse of an accepted request identity.
    private fun matchesPersistedSubmission(
        request: ChatSubmission,
        turn: com.helix.core.storage.entity.TurnEntity,
    ): Boolean {
        if (turn.sessionId != request.sessionId) return false
        val message =
            storage.messages
                .allRevisions(request.sessionId)
                .firstOrNull { it.turnId == turn.id && it.role == "USER" } ?: return false
        val bindings = storage.messageAttachments.listByMessage(message.id)
        if (bindings.map { it.artifactId } != request.attachmentIds) return false
        val references = storage.messageReferenceSnapshots.forMessage(message.id)
        if (references.map { it.sourceSessionId to it.selectionKind } !=
            listOfNotNull(request.referenceSourceSessionId?.let { it to requireNotNull(request.referenceKind) })
        ) {
            return false
        }
        val body = storage.messages.readContentBounded(message, 8 * 1024 * 1024).orEmpty()
        if (AttachmentContext.authoredPrefix(body, bindings.size) != request.text) return false
        val expected =
            TurnInputFingerprint.of(
                body,
                bindings.map {
                    com.helix.core.agent.turn.TurnInputAttachment(
                        it.artifactId,
                        it.purpose,
                        it.boundSha256,
                    )
                },
                references.map {
                    ConversationReferenceSnapshotInput(
                        it.sourceSessionId,
                        it.sourceSessionTitle,
                        it.selectionKind,
                        it.sourceMessageIds,
                        it.content,
                    )
                },
                revisedMessageId = request.revisedMessageId,
                recoveryFromTurnId = turn.recoveryFromTurnId,
            )
        return turn.inputFingerprint == expected
    }
}

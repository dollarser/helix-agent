package com.helix.app.infrastructure.conversation

import com.helix.app.internal.LineStore
import com.helix.feature.conversation.application.ConversationLaunchStore
import com.helix.feature.conversation.application.ConversationLaunchTarget

/**
 * App-level navigation preference for the conversation-first shell.
 *
 * This is deliberately NOT Room product state: it records which conversation surface the user
 * last chose, never execution truth. A missing/invalid value fails closed to [NewDraft].
 */

class LineConversationLaunchStore internal constructor(
    private val backing: LineStore,
) : ConversationLaunchStore {
    /** UI preference only. An absent or explicitly empty choice never overwrites a saved session. */
    override fun draftModel(): Pair<String, String>? {
        val rows = backing.lines(MODEL_KEY)
        return rows.takeIf { it.size == 2 && it.all(::validModelPart) }?.let { it[0] to it[1] }
    }

    override fun draftModelFor(current: Pair<String?, String?>?): Pair<String?, String?> =
        current ?: draftModel() ?: (null to null)

    override fun selectDraftModel(
        providerId: String?,
        modelId: String?,
    ) {
        require(
            (providerId == null && modelId == null) ||
                (providerId != null && modelId != null && validModelPart(providerId) && validModelPart(modelId)),
        )
        val choice = if (providerId == null) listOf("none") else listOf(providerId, requireNotNull(modelId))
        backing.setLines(MODEL_KEY, choice)
    }

    private fun validModelPart(value: String): Boolean =
        value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }

    override fun target(): ConversationLaunchTarget {
        val rows = backing.lines(KEY)
        return when {
            rows == listOf(NEW_DRAFT) -> {
                ConversationLaunchTarget.NewDraft
            }

            rows.size == 2 && rows[0] == SESSION && validSessionId(rows[1]) -> {
                ConversationLaunchTarget.Session(rows[1])
            }

            else -> {
                ConversationLaunchTarget.NewDraft
            }
        }
    }

    override fun selectNewDraft() {
        backing.setLines(KEY, listOf(NEW_DRAFT))
    }

    override fun selectSession(sessionId: String) {
        require(validSessionId(sessionId)) { "invalid conversation launch session id" }
        backing.setLines(KEY, listOf(SESSION, sessionId))
    }

    private fun validSessionId(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= MAX_SESSION_ID_CHARS &&
            '\n' !in value &&
            '\r' !in value &&
            '\u0000' !in value

    private companion object {
        const val KEY = "conversation_launch_target"
        const val MODEL_KEY = "conversation_draft_model"
        const val NEW_DRAFT = "new"
        const val SESSION = "session"
        const val MAX_SESSION_ID_CHARS = 512
    }
}

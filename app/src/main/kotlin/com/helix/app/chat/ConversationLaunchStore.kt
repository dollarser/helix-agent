package com.helix.app.chat

import com.helix.app.internal.LineStore

/**
 * App-level navigation preference for the conversation-first shell.
 *
 * This is deliberately NOT Room product state: it records which conversation surface the user
 * last chose, never execution truth. A missing/invalid value fails closed to [NewDraft].
 */
internal sealed interface ConversationLaunchTarget {
    data object NewDraft : ConversationLaunchTarget

    data class Session(
        val sessionId: String,
    ) : ConversationLaunchTarget
}

class ConversationLaunchStore internal constructor(
    private val backing: LineStore,
) {
    internal fun target(): ConversationLaunchTarget {
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

    internal fun selectNewDraft() {
        backing.setLines(KEY, listOf(NEW_DRAFT))
    }

    internal fun selectSession(sessionId: String) {
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
        const val NEW_DRAFT = "new"
        const val SESSION = "session"
        const val MAX_SESSION_ID_CHARS = 512
    }
}

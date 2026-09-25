package com.helix.app

import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmission
import java.util.UUID

/** Test-only typed chat entry; production code has no text-only send shortcut. */
internal fun ChatService.sendTestMessage(
    sessionId: String,
    text: String,
) {
    sendSubmission(ChatSubmission(sessionId, 0, UUID.randomUUID().toString(), text))
}

internal fun ChatService.sendTestMessage(text: String) {
    val sessionId = requireNotNull(screen.value.openSessionId) { "test chat session is not open" }
    sendTestMessage(sessionId, text)
}

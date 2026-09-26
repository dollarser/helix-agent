package com.helix.app

import com.helix.app.chat.ChatService
import com.helix.app.chat.ChatSubmission
import java.util.UUID

/**
 * Test-only typed chat entry; production code has no text-only send shortcut.
 *
 * Mirrors the production `send()` default (`ChatService`: `ChatSubmission(session.id, 0,
 * idGenerator(), text, staged.map { it.artifactId })`): the submission must carry the live
 * staged attachment ids. `ChatService.admitSubmission` refuses any submission whose
 * `attachmentIds` differ from the staged list with `ATTACHMENTS_CHANGED`, so a helper that
 * left them empty silently rejected every attachment send and no egress disclosure ever
 * appeared.
 */
internal fun ChatService.sendTestMessage(
    sessionId: String,
    text: String,
) {
    val stagedAttachmentIds = screen.value.pendingAttachments.map { it.id }
    sendSubmission(
        ChatSubmission(
            sessionId,
            0,
            UUID.randomUUID().toString(),
            text,
            stagedAttachmentIds,
        ),
    )
}

internal fun ChatService.sendTestMessage(text: String) {
    val sessionId = requireNotNull(screen.value.openSessionId) { "test chat session is not open" }
    sendTestMessage(sessionId, text)
}

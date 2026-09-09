package com.helix.app.chat

import com.helix.core.storage.entity.SessionEntity

internal data class SessionDraft(
    val session: SessionEntity,
    val attachments: List<DraftAttachment> = emptyList(),
)

internal data class DraftAttachment(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
)

internal fun automaticSessionTitle(text: String): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    val end = clean.offsetByCodePoints(0, minOf(20, clean.codePointCount(0, clean.length)))
    return clean.substring(0, end)
}

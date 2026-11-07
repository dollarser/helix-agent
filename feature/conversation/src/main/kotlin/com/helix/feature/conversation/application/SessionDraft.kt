package com.helix.feature.conversation.application

import com.helix.core.agent.RunControlConfig

/** Metadata selected before a session is materialized. Contains no database state or search index. */
data class DraftSession(
    val id: String,
    val title: String,
    val providerId: String?,
    val modelId: String?,
    val createdAt: Long,
    val directoryRef: String? = null,
)

data class SessionDraft(
    val session: DraftSession,
    val control: RunControlConfig,
    val attachments: List<DraftAttachment> = emptyList(),
    val project: DraftProject? = null,
)

data class DraftAttachment(
    val id: String,
    val uri: String,
    val name: String,
    val size: Long,
    val isImage: Boolean = false,
)

fun automaticSessionTitle(text: String): String {
    val clean = text.replace("\u0000", "").trim().replace(Regex("\\s+"), " ")
    val end = clean.offsetByCodePoints(0, minOf(48, clean.codePointCount(0, clean.length)))
    return clean.substring(0, end)
}

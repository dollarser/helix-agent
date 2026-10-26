package com.helix.core.storage.knowledge

/** Open document value; documentHash is an observed revision, never a release version. */
data class KnowledgeNote(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String = "DRAFT",
    val generatedDraft: String = "",
    val generationState: String = "IDLE",
    val generationTarget: String = "",
    val kind: String = "NOTE",
    val originReferencesJson: String = "[]",
    val originSessionId: String = "",
    val originMessageId: String = "",
    val originContentHash: String = "",
    val documentHash: String = "",
)

/** Frozen evidence selected by the user. Source re-extraction never changes saved note evidence. */
data class KnowledgeNoteSource(
    val noteId: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val receivedAt: Long,
    val excerpt: String,
    val contentHash: String,
    val completeness: String,
)

package com.helix.core.storage.knowledge

/** Retained answer document, separate from a reviewed note. */
data class KnowledgeAnswer(
    val id: String,
    val question: String,
    val createdAt: Long,
    val state: String,
    val sourcesJson: String,
    val targetJson: String,
    val body: String = "",
    val savedNoteId: String = "",
    val documentHash: String = "",
    val pendingNoteId: String = "",
)

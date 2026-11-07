package com.helix.core.model.input

/** A selected text revision, never a body snapshot or a grant to the whole library. */
data class KnowledgeReference(
    val kind: String,
    val resourceId: String,
    val title: String,
    val sourceHash: String,
    val characters: Int,
    val completeness: String = "USER_CONTENT",
) {
    init {
        require(kind in setOf("NOTE", "KNOWLEDGE", "SOURCE"))
        require(resourceId.matches(Regex("[\\p{L}\\p{N}_-]{1,100}")))
        require(title.isNotBlank() && title.length <= 160 && '\u0000' !in title)
        require(sourceHash.matches(Regex("[a-f0-9]{64}")))
        require(characters in 1..200_000)
        require(completeness.matches(Regex("[A-Z_]{1,40}")))
    }
}

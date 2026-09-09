package com.helix.core.agent

/** User-described success criterion. Its semantic assessment belongs to the model (ADR-0040). */
data class Criterion(
    val id: String,
    val description: String,
) {
    init {
        require(id.length in 1..MAX_ID_LENGTH && id.all { it in ID_CHARS })
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_LENGTH)
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        private val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toSet()
    }
}

package com.helix.app.chat

/** Retries have no new USER row; trace back to the nearest original request, never a future message. */
internal object RetryMessageSource {
    fun resolve(
        target: String,
        chronologicalTurns: List<String>,
        userTurns: Set<String>,
    ): String? {
        val index = chronologicalTurns.indexOf(target)
        if (index < 0) return null
        return chronologicalTurns.take(index + 1).lastOrNull { it in userTurns }
    }
}

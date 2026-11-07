package com.helix.feature.conversation.presentation

/** Transient host display lifecycle, kept separate from durable Turn state and saved observations. */
enum class DisplayExecutionState { CREATING, ACTIVE, RELEASED, CONNECTION_LOST, RELEASE_UNKNOWN, UNAVAILABLE, UNKNOWN }

data class DisplayExecutionUi(
    val sessionId: String,
    val turnId: String,
    val state: DisplayExecutionState,
    val lastObservedPackage: String? = null,
    val generation: Long = 0,
)

fun mergeDisplayExecution(
    previous: List<DisplayExecutionUi>,
    value: DisplayExecutionUi,
): List<DisplayExecutionUi> {
    val current = previous.firstOrNull { it.sessionId == value.sessionId && it.turnId == value.turnId }
    if (current != null && current.generation > value.generation) return previous
    return previous.filterNot { it.sessionId == value.sessionId && it.turnId == value.turnId } + value
}

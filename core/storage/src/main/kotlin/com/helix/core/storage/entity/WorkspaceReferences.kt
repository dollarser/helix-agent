package com.helix.core.storage.entity

/**
 * Durable retention facts, not permission to delete. The cleanup owner must obtain admission and
 * re-read these facts in the same transaction that fences the resource against new bindings.
 * Historical requests and artifacts retain their original resource after a directory switch.
 */
data class WorkspaceReferences(
    val ownerSessions: Int,
    val sessionBindings: Int,
    val modelRequests: Int,
    val artifacts: Int,
    val pendingCalls: Int = 0,
) {
    init {
        require(listOf(ownerSessions, sessionBindings, modelRequests, artifacts, pendingCalls).all { it >= 0 })
    }

    val retained: Boolean
        get() = listOf(ownerSessions, sessionBindings, modelRequests, artifacts, pendingCalls).any { it > 0 }
}

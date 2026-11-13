package com.helix.core.model.input

/** Host-bound provenance. Forwarded model text is never a new owner instruction or approval. */
data class ModelDelegationOrigin(
    val sourceSessionId: String,
    val sourceTurnId: String,
    val sourceCallId: String,
    val requestKey: String,
    val grantRevision: Long,
) {
    init {
        listOf(sourceSessionId, sourceTurnId, sourceCallId, requestKey).forEach {
            require(it.isNotBlank() && it.length <= 128 && it.none { char -> char.code < 0x20 })
        }
        require(grantRevision > 0)
    }
}

enum class SessionInputOrigin { USER, MODEL_DELEGATION }

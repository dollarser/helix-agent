package com.helix.core.agent

import kotlinx.serialization.json.JsonObject

/** Host-validated immutable metadata; resultRef points to the original audit observation, not an output artifact. */
data class JobContextCandidate(
    val identity: String,
    val revision: String,
    val observedAtMillis: Long,
    val resultRef: String,
    val terminal: Boolean,
    val facts: JsonObject,
) {
    init {
        require(identity.length in 1..512 && revision.length in 1..128 && resultRef.length in 1..256)
        require(observedAtMillis >= 0 && facts.toString().toByteArray().size <= 4096)
    }
}

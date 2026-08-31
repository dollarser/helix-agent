package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import com.helix.core.model.ToolCallId

/**
 * Verifier-backed evidence that one acceptance criterion is met (modes doc section 6.2: only
 * real ToolResult/Artifact verifier evidence may support COMPLETED). `verifier` names the
 * check that produced the evidence; at least one concrete reference (tool result or artifact)
 * must be present, so a bare claim can never satisfy a criterion.
 */
data class CriterionEvidence(
    val verifier: String,
    val artifactRef: ArtifactRef?,
    val toolCallId: ToolCallId?,
) {
    init {
        require(verifier.isNotBlank()) { "verifier must not be blank" }
        require(verifier.length <= MAX_VERIFIER_LENGTH) {
            "verifier must be <= $MAX_VERIFIER_LENGTH characters"
        }
        require(artifactRef != null || toolCallId != null) {
            "evidence requires an artifactRef or toolCallId"
        }
    }

    companion object {
        const val MAX_VERIFIER_LENGTH = 128
    }
}

/**
 * One acceptance criterion of a Goal. A criterion is satisfied only by [CriterionEvidence];
 * the reducer enforces that satisfaction always carries evidence.
 */
data class Criterion(
    val id: String,
    val description: String,
    val evidence: CriterionEvidence? = null,
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "id must be <= $MAX_ID_LENGTH characters" }
        require(id.all { it in ID_CHARS }) { "id must match [A-Za-z0-9_-]" }
        require(description.isNotBlank()) { "description must not be blank" }
        require(description.length <= MAX_DESCRIPTION_LENGTH) {
            "description must be <= $MAX_DESCRIPTION_LENGTH characters"
        }
    }

    val isSatisfied: Boolean
        get() = evidence != null

    fun withEvidence(newEvidence: CriterionEvidence): Criterion = copy(evidence = newEvidence)

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024

        private val ID_CHARS: Set<Char> =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toSet()
    }
}

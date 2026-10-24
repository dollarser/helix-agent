package com.helix.core.agent

/** Host-selected retention only; neither value changes provenance, authority or tool access. */
enum class PromptContentRetention {
    REQUIRED,
    OPTIONAL,
}

/** Source version is distinct from the hash of the final framed content actually sent. */
data class PromptContentIdentity(
    val owner: String,
    val revision: String,
    val resource: String? = null,
    val owners: List<PromptContentOwner> = emptyList(),
)

/** A hint to an already admitted scoped tool, never an execution request or authorization. */
data class PromptContentReadReference(
    val tool: String,
    val argumentsJson: String,
)

/** Versioned source bindings; these describe selection provenance, never runtime authority. */
data class PromptContentOwner(
    val id: String,
    val runtimeId: String,
    val version: String,
    val revision: Long,
    val fingerprint: String,
)

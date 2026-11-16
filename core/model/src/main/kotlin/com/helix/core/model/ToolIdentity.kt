package com.helix.core.model

/**
 * Stable portable tool identity shared by registries and model protocols.
 * Product tools use lowercase snake_case. External adapters construct source-qualified names
 * before registration and retain the remote server name separately for dispatch.
 */
@JvmInline
value class ToolName(
    val value: String,
) {
    init {
        require(PORTABLE.matches(value)) {
            "tool name must be 1..64 ASCII letters, digits, underscores or hyphens"
        }
    }

    override fun toString(): String = value

    private companion object {
        val PORTABLE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * Non-negative tool API contract label; owner-authorized preview declarations currently use 1.
 * The exact per-call proof includes this label, while persistent user permissions use stable
 * source, tool and approved scope. Contract hashes and runtime incarnations protect replacement.
 */
@JvmInline
value class ToolVersion(
    val value: Int,
) {
    init {
        require(value >= 0) { "tool version must be >= 0" }
    }

    override fun toString(): String = value.toString()
}

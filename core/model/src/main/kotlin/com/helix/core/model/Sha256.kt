package com.helix.core.model

/**
 * A SHA-256 digest as exactly 64 lowercase hexadecimal characters.
 *
 * Used for content hashes (`artifacts.sha256`, context item `contentHash`), canonical argument
 * hashes (`tool_calls.argsHash`), tool schema hashes and MCP/Skill snapshot hashes. The
 * lowercase-only invariant keeps persisted and compared hashes canonical.
 */
@JvmInline
value class Sha256(
    val hex: String,
) {
    init {
        require(hex.length == HEX_LENGTH) { "sha256 must be $HEX_LENGTH hex characters" }
        hex.forEach { c ->
            require(c in '0'..'9' || c in 'a'..'f') { "sha256 must be lowercase hex" }
        }
    }

    override fun toString(): String = hex

    companion object {
        const val HEX_LENGTH = 64

        /** Parses `raw`, normalizing uppercase input; rejects anything that is not 64 hex chars. */
        fun fromHex(raw: String): Sha256 = Sha256(raw.lowercase())
    }
}

package com.helix.core.model

/**
 * A Keystore/SecretStore reference for a provider credential (architecture doc section 6.2:
 * the secret itself lives only in the Android Keystore-backed SecretStore; Room, logs and
 * UI state hold this alias at most).
 *
 * The alias doubles as a file name component of the per-alias secret store layout, so the
 * character set is deliberately restricted to letters/digits plus `.`, `_`, `-` with an
 * alphanumeric start — this makes path traversal and hidden-file injection impossible at
 * construction time.
 */
@JvmInline
value class SecretAlias(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "secret alias must be 1..$MAX_LENGTH chars" }
        require(value.first().isLetterOrDigit()) { "secret alias must start with a letter or digit" }
        require(value.all { c -> c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' }) {
            "secret alias allows letters, digits, '.', '_' and '-' only"
        }
    }

    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 128
    }
}

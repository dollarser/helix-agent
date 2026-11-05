package com.helix.extensions.mobileuse.automation

/** Bounded process-local diagnostic tombstones, never an authorization or node lookup source. */
internal class RetiredNodeTokens {
    private val tokens = linkedSetOf<String>()

    fun remember(retired: Collection<String>) {
        tokens.addAll(retired)
        while (tokens.size > NodeTokenRegistry.MAX_TOKENS * 2) {
            tokens.remove(tokens.first())
        }
    }

    operator fun contains(token: String): Boolean = token in tokens
}

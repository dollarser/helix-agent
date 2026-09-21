package com.helix.extensions.mcp.oauth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * RFC 7636 PKCE (Proof Key for Code Exchange) generation and verification.
 * ADR-CONNECTORS-002: "使用 PKCE S256 和每次新的高熵 state".
 */
data class McpOAuthPkce(
    val verifier: String,
    val challenge: String,
    val method: String = METHOD_S256,
) {
    companion object {
        const val METHOD_S256 = "S256"
        private const val VERIFIER_ENTROPY_BYTES = 32
        private const val STATE_ENTROPY_BYTES = 24

        fun generate(secureRandom: SecureRandom = SecureRandom()): McpOAuthPkce {
            val bytes = ByteArray(VERIFIER_ENTROPY_BYTES)
            secureRandom.nextBytes(bytes)
            val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            val challenge = computeChallenge(verifier)
            return McpOAuthPkce(verifier, challenge, METHOD_S256)
        }

        fun generateState(secureRandom: SecureRandom = SecureRandom()): String {
            val bytes = ByteArray(STATE_ENTROPY_BYTES)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun computeChallenge(verifier: String): String {
            require(verifier.length in 43..128) { "code_verifier length must be between 43 and 128 chars" }
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(verifier.toByteArray(StandardCharsets.US_ASCII))
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
        }

        fun verify(
            verifier: String,
            expectedChallenge: String,
        ): Boolean {
            if (verifier.length !in 43..128) return false
            val computed = computeChallenge(verifier)
            return MessageDigest.isEqual(
                computed.toByteArray(StandardCharsets.US_ASCII),
                expectedChallenge.toByteArray(StandardCharsets.US_ASCII),
            )
        }
    }
}

typealias McpOAuthPkceChallenge = McpOAuthPkce

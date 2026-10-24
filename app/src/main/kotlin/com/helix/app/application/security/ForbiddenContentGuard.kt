package com.helix.app.application.security

/**
 * Credential-shaped content detection (ADR-0005: forbidden content — API key,
 * OAuth token, Cookie, password, verification code, auth fields and other
 * credentials; BOTH safety profiles refuse to send it).
 *
 * The patterns are deliberately CONSERVATIVE and fixed: a hit blocks the send
 * with a STABLE code (never locale text — HXA-069: the code is resolved to the
 * current locale by the Android-side caller) and the matched content is never
 * echoed. A false positive is harmless (the user removes the secret and
 * re-sends); a false negative here is the dangerous direction, so new patterns
 * are only added with tests.
 */
object ForbiddenContentGuard {
    /** The stable rejection code [reasonFor] returns on a hit (resolved to a localized label by the caller). */
    const val CREDENTIAL_DETECTED = "CREDENTIAL_DETECTED"

    /** Returns the stable rejection code, or null when the text is sendable. */
    fun reasonFor(text: String): String? {
        for (pattern in PATTERNS) {
            if (pattern.containsMatchIn(text)) return CREDENTIAL_DETECTED
        }
        return null
    }

    /** Export reuses the same known-credential patterns without changing egress decisions. */
    fun redactKnownCredentials(text: String): String {
        // A PEM header match identifies a secret whose remaining lines must not survive the replacement.
        if (text.contains("-----BEGIN ") && text.contains("PRIVATE KEY-----")) return "[redacted: private key]"
        return PATTERNS.fold(text) { result, pattern -> pattern.replace(result, "[redacted: credential]") }
    }

    private val PATTERNS: List<Regex> =
        listOf(
            // OpenAI / OpenAI-compatible family keys (sk-, incl. sk-ant- Anthropic).
            Regex("sk-[A-Za-z0-9_-]{16,}"),
            // AWS access key ids.
            Regex("AKIA[0-9A-Z]{16}"),
            // GitHub personal/access tokens (ghp/gho/ghu/ghs/ghr).
            Regex("gh[pousr]_[A-Za-z0-9]{16,}"),
            // Slack tokens.
            Regex("xox[baprs]-[A-Za-z0-9-]{8,}"),
            // PEM private key headers.
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            // Authorization bearer tokens (generic shape: >= 20 base64url chars).
            Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{20,}"),
            // JSON/YAML-style password fields with a non-placeholder value.
            Regex("(?i)\\bpassword\\b\\s*[:=]\\s*[\"']?[A-Za-z0-9!@#$%^&*()_+]{6,}"),
        )
}

package com.helix.core.policy

import com.helix.core.policy.ForbiddenContentGuard

/** Model-facing diagnostics use the existing credential patterns plus current installation secret values. */
object DiagnosticRedaction {
    fun redact(
        text: String,
        secrets: Set<String> = emptySet(),
    ): String {
        val masked =
            secrets
                .filter { it.length >= 4 }
                .sortedByDescending { it.length }
                .fold(text) { value, secret -> value.replace(secret, "[redacted: credential]") }
        return ForbiddenContentGuard.redactKnownCredentials(masked)
    }
}

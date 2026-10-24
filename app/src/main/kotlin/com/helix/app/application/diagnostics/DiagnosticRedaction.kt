package com.helix.app.application.diagnostics

import com.helix.app.application.security.ForbiddenContentGuard

/** Model-facing diagnostics use the existing credential patterns plus current installation secret values. */
internal object DiagnosticRedaction {
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

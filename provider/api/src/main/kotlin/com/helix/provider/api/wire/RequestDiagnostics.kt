package com.helix.provider.api.wire

import java.util.UUID

/** Diagnostic identity only. Never used for authorization or replay/idempotency. */
public object RequestDiagnostics {
    @Volatile private var identity = Identity("helix/development", UUID.randomUUID().toString())

    public fun configure(
        version: String,
        installationId: String,
    ) {
        require(version.matches(Regex("[A-Za-z0-9./_-]{1,128}")))
        require(installationId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        identity = Identity(version, installationId)
    }

    public fun headers(headers: Map<String, String>): Map<String, String> {
        val current = identity
        val id =
            headers.entries
                .firstOrNull { it.key.equals("X-Client-Request-ID", true) }
                ?.value
                ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,128}")) } ?: UUID.randomUUID().toString()
        return headers.filterKeys { it.lowercase() !in names } +
            mapOf(
                "X-Client-Version" to current.version,
                "X-Device-ID" to current.installationId,
                "User-Agent" to current.version,
                "X-Client-Request-ID" to id,
            )
    }

    private data class Identity(
        val version: String,
        val installationId: String,
    )

    private val names = setOf("x-client-version", "x-device-id", "user-agent", "x-client-request-id")
}

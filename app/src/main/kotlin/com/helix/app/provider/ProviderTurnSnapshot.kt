package com.helix.app.provider

import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig

/** Canonical Turn-bound provider facts shared by admission, steering revalidation and review resume. */
internal object ProviderTurnSnapshot {
    suspend fun capture(
        service: ProviderService,
        providerId: String,
        modelId: String?,
    ): String = encode(service.storedConfig(providerId), modelId)

    fun encode(
        config: ProviderConfig,
        modelId: String?,
    ): String {
        val capabilitiesJson =
            runCatching {
                ProviderCapabilities.parse(config.capabilitySnapshot).let { caps ->
                    ",\"capabilities\":${ProviderCapabilities.toJsonString(caps)}"
                }
            }.getOrDefault("")
        return buildString {
            append("{\"displayName\":\"")
            append(jsonEscape(config.displayName))
            append("\",\"transportIdentity\":\"")
            append(jsonEscape(config.transport.cacheKey))
            append("\",\"model\":\"")
            append(jsonEscape(modelId ?: config.model))
            append("\"")
            append(capabilitiesJson)
            append("}")
        }
    }

    private fun jsonEscape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}

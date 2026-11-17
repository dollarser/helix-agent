package com.helix.provider.api

/** Untrusted diagnostic text, never an instruction or a retry/permission decision. */
internal object ServerErrorSummary {
    fun redact(
        text: String,
        secrets: Collection<String>,
    ): String {
        var value = text.filter { !it.isISOControl() || it in "\n\t" }
        if (value.contains("PRIVATE KEY-----")) return "[redacted: private key]"
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach {
            value = value.replace(it, "[redacted: credential]")
        }
        credentialPatterns.forEach { value = it.replace(value, "[redacted: credential]") }
        value = opaqueToken.replace(value, "[redacted: credential]")
        return prefix(value)
    }

    // Only protocol-owned syntax is exempt from the opaque-token heuristic. Upstream
    // metadata is untrusted: arbitrary names/paths must still use the default redactor.
    fun parameter(
        text: String,
        secrets: Collection<String>,
    ): String = if (toolSchemaParameter.matches(text)) redactKnown(text, secrets) else redact(text, secrets)

    fun code(
        text: String,
        secrets: Collection<String>,
    ): String = if (text in protocolCodes) redactKnown(text, secrets) else redact(text, secrets)

    private fun redactKnown(
        text: String,
        secrets: Collection<String>,
    ): String {
        var value = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach {
            value = value.replace(it, "[redacted: credential]")
        }
        credentialPatterns.forEach { value = it.replace(value, "[redacted: credential]") }
        return prefix(value)
    }

    private val protocolCodes =
        setOf(
            "invalid_function_parameters",
            "password_change_required",
            "provider_auth_failed",
            "provider_credit_exhausted",
            "provider_region_restricted",
            "global_cloud_disabled",
            "global_budget_exhausted",
            "account_luna_budget_exhausted",
            "account_asr_budget_exhausted",
            "account_tts_budget_exhausted",
            "account_daily_limit",
            "key_token_budget_exhausted",
            "provider_rate_limited",
            "account_rate_limited",
            "account_concurrency_limited",
            "provider_stream_interrupted",
            "unsupported_audio_format",
        )

    private val toolSchemaParameter = Regex("tools\\[[0-9]{1,6}\\](?:\\.function)?\\.parameters")

    private fun prefix(value: String): String {
        val bytes = value.toByteArray()
        if (bytes.size <= MAX_BYTES) return value
        var end = MAX_BYTES
        while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
        return bytes.copyOf(end).toString(Charsets.UTF_8)
    }

    private const val MAX_BYTES = 768
    private val opaqueToken = Regex("[A-Za-z0-9_~+/=.-]{20,}")
    private val credentialPatterns =
        listOf(
            Regex("(?i)bearer\\s+[^\\s,;\\\"']+"),
            Regex("(?i)(?:authorization|api[-_]?key|token|password|cookie)\\s*[=:]\\s*[^\\s,;]+"),
            Regex("(?:sk-|gh[pousr]_|xox[baprs]-)[A-Za-z0-9_-]{8,}"),
        )
}

package com.helix.runtime.cli.app

import okhttp3.HttpUrl.Companion.toHttpUrl

/** A catalog compatibility parameter, not a downloaded client or a permission/capability assertion. */
internal class CodexClientVersionSettings(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    fun current(): String = read()?.takeIf(::valid) ?: DEFAULT

    fun save(input: String): String {
        val normalized = input.trim()
        require(valid(normalized)) { "CODEX_CLIENT_VERSION_INVALID" }
        write(normalized)
        return normalized
    }

    fun reset(): String {
        write(null)
        return DEFAULT
    }

    companion object {
        // Official stable CLI 2026-10-01 release, checked 2026-10-05; never follow alpha automatically.
        const val DEFAULT = "0.160.0"
        private val version =
            Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?")

        fun valid(value: String): Boolean = value.length in 5..64 && version.matches(value)

        fun catalogUrl(value: String): String {
            require(valid(value)) { "CODEX_CLIENT_VERSION_INVALID" }
            return CodexSubscriptionSmoke.MODELS_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("client_version", value)
                .build()
                .toString()
        }
    }
}

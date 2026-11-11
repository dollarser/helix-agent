package com.helix.app.search

import android.content.Context
import com.helix.app.internal.LineStore
import com.helix.app.internal.PrefsLineStore
import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.tools.android.WebSearchConfiguration
import com.helix.tools.android.WebSearchCredential
import com.helix.tools.android.WebSearchProvider

/** Only provider selection and opt-in are plaintext. No key is returned to UI state or model context. */
class WebSearchSettings(
    private val lines: LineStore,
    private val secrets: SecretStore,
) : WebSearchCredential {
    val provider: WebSearchProvider get() =
        WebSearchProvider.entries.firstOrNull { it.name == lines.lines("configuration").firstOrNull() }
            ?: WebSearchProvider.TAVILY
    val enabled: Boolean get() {
        val configuration = lines.lines("configuration")
        return configuration.getOrNull(1) == "true" &&
            WebSearchProvider.entries.any { it.name == configuration.firstOrNull() }
    }
    val hasKey: Boolean get() = hasKey(provider)

    fun hasKey(provider: WebSearchProvider): Boolean = secrets.contains(keyAlias(provider))

    fun save(
        enabled: Boolean,
        newKey: String,
        provider: WebSearchProvider = this.provider,
    ) = synchronized(lock) {
        if (newKey.isNotEmpty()) {
            require(newKey.length <= 4096 && newKey.all { it in '!'..'~' })
            secrets.put(keyAlias(provider), newKey)
        }
        require(!enabled || hasKey(provider))
        lines.setLines("configuration", listOf(provider.name, enabled.toString()))
    }

    fun removeKey(provider: WebSearchProvider = this.provider) =
        synchronized(lock) {
            val selected = provider
            lines.setLines("configuration", listOf(selected.name, "false"))
            secrets.delete(keyAlias(selected))
        }

    override fun configuredSearch(): WebSearchConfiguration? =
        synchronized(lock) {
            val configuration = lines.lines("configuration")
            if (configuration.getOrNull(1) != "true") return@synchronized null
            val selected =
                WebSearchProvider.entries.firstOrNull { it.name == configuration.firstOrNull() }
                    ?: return@synchronized null
            if (!hasKey(selected)) return@synchronized null
            WebSearchConfiguration(selected, secrets.get(keyAlias(selected)))
        }

    companion object {
        private val lock = Any()

        private fun keyAlias(provider: WebSearchProvider) = SecretAlias("web-search.${provider.name.lowercase()}")

        fun create(
            context: Context,
            secrets: SecretStore,
        ): WebSearchSettings = WebSearchSettings(PrefsLineStore(context, "web-search", synchronous = true), secrets)
    }
}

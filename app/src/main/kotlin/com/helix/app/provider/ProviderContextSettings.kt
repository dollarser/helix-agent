package com.helix.app.provider

import com.helix.app.internal.LineStore
import java.security.MessageDigest

/** Per endpoint/model context policy; independent of cumulative Turn/Goal budgets. */
data class ProviderContextSettings(
    val manualWindow: Long? = null,
    val serverWindow: Long? = null,
    val autoCompact: Boolean = true,
    val triggerPercent: Int = 80,
) {
    init {
        require(manualWindow == null || manualWindow in MIN_WINDOW..MAX_WINDOW)
        require(serverWindow == null || serverWindow in MIN_WINDOW..MAX_WINDOW)
        require(triggerPercent in 10..95)
    }

    val window: Long get() = listOfNotNull(manualWindow, serverWindow).minOrNull() ?: DEFAULT_WINDOW

    companion object {
        const val DEFAULT_WINDOW = 200_000L
        const val MIN_WINDOW = 1024L
        const val MAX_WINDOW = 1_000_000L
    }
}

class ProviderContextSettingsStore(
    private val store: LineStore,
) {
    fun read(
        providerId: String,
        endpoint: String,
        model: String,
    ): ProviderContextSettings {
        val lines = store.lines(key(providerId, endpoint, model))
        return runCatching {
            if (lines.size != 4) {
                ProviderContextSettings()
            } else {
                ProviderContextSettings(
                    lines[0].toLongOrNull(),
                    lines[1].toLongOrNull(),
                    lines[2].toBooleanStrict(),
                    lines[3].toInt(),
                )
            }
        }.getOrElse { ProviderContextSettings() }
    }

    fun write(
        providerId: String,
        endpoint: String,
        model: String,
        settings: ProviderContextSettings,
    ) {
        store.setLines(
            key(providerId, endpoint, model),
            listOf(
                settings.manualWindow?.toString().orEmpty(),
                settings.serverWindow?.toString().orEmpty(),
                settings.autoCompact.toString(),
                settings.triggerPercent.toString(),
            ),
        )
    }

    private fun key(
        providerId: String,
        endpoint: String,
        model: String,
    ): String {
        val encoded = listOf(providerId, endpoint, model).joinToString("") { "${it.length}:$it" }
        return "context-v1-" +
            MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

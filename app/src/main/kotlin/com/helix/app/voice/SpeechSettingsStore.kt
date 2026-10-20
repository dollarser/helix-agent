package com.helix.app.voice

import android.content.Context
import com.helix.app.internal.LineStore
import com.helix.app.internal.PrefsLineStore

data class SpeechServiceSettings(
    val httpEnabled: Boolean = false,
    val asrEndpoint: String = "",
    val ttsEndpoint: String = "",
    val useAccountLogin: Boolean = false,
) {
    fun session(
        endpoint: String,
        account: com.helix.app.account.AccountSession? = null,
        transport: com.helix.provider.api.wire.WireClient? = null,
    ): HttpSpeechSession {
        val config = HttpSpeechSession.config(endpoint)
        val target = config.endpoint.toString()
        val wire =
            if (useAccountLogin) {
                val owner = requireNotNull(account)
                if (transport == null) owner.wire(target) else owner.wire(target, transport)
            } else {
                transport
            }
        return if (wire == null) HttpSpeechSession(config) else HttpSpeechSession(config, wire)
    }

    fun validate() {
        if (httpEnabled || asrEndpoint.isNotBlank()) HttpSpeechSession.config(asrEndpoint)
        if (httpEnabled || ttsEndpoint.isNotBlank()) HttpSpeechSession.config(ttsEndpoint)
        if (useAccountLogin) {
            com.helix.app.account.AccountSession
                .validateTarget(asrEndpoint)
            com.helix.app.account.AccountSession
                .validateTarget(ttsEndpoint)
        }
    }
}

/** Only the current explicit account-aware format is supported; retired public-ID settings are inert. */
class SpeechSettingsStore(
    private val store: LineStore,
) {
    constructor(context: Context) : this(PrefsLineStore(context, "speech-services", synchronous = true))

    fun read(): SpeechServiceSettings {
        val rows = store.lines("settings")
        return when {
            rows.isEmpty() -> {
                SpeechServiceSettings()
            }

            rows.size == 5 && rows[0] == "account-v1" && rows[1] in setOf("true", "false") &&
                rows[4] in setOf("true", "false") -> {
                SpeechServiceSettings(rows[1].toBooleanStrict(), rows[2], rows[3], rows[4].toBooleanStrict())
            }

            else -> {
                SpeechServiceSettings(httpEnabled = true)
            }
        }
    }

    fun save(settings: SpeechServiceSettings) {
        settings.validate()
        store.setLines(
            "settings",
            listOf(
                "account-v1",
                settings.httpEnabled.toString(),
                settings.asrEndpoint,
                settings.ttsEndpoint,
                settings.useAccountLogin.toString(),
            ),
        )
    }

    fun asrFactory(context: Context): StreamingAsrFactory {
        val settings = read()
        return if (settings.httpEnabled) {
            CloudAsrFactory(settings.asrEndpoint, supportsPartial = false) {
                HttpFileAsr(
                    settings.session(
                        settings.asrEndpoint,
                        (context.applicationContext as com.helix.app.HelixApplication).account,
                    ),
                )
            }
        } else {
            OfflineAsrFactory(context.assets)
        }
    }
}

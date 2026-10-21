package com.helix.app.voice

import android.content.Context
import com.helix.app.account.speechSocket
import com.helix.app.internal.LineStore
import com.helix.app.internal.PrefsLineStore

data class SpeechServiceSettings(
    val httpEnabled: Boolean = false,
    val asrEndpoint: String = "",
    val ttsEndpoint: String = "",
    val useAccountLogin: Boolean = false,
    val webSocket: Boolean = false,
) {
    fun session(
        endpoint: String,
        account: com.helix.app.account.AccountSession? = null,
        transport: com.helix.provider.api.wire.WireClient? = null,
    ): HttpSpeechSession {
        val config = HttpSpeechSession.config(endpoint)
        val target = config.endpoint.toString()
        val delegate =
            transport ?: com.helix.provider.api.wire.OkHttpWireClient(
                connectTimeoutMillis = 30000,
                readTimeoutMillis = config.timeoutMillis.toLong(),
                maxBodyBytes = 12_000_000,
                followRedirects = false,
                retryConnections = false,
            )
        val wire =
            if (useAccountLogin) {
                val owner = requireNotNull(account)
                owner.wire(target, delegate)
            } else {
                delegate
            }
        return HttpSpeechSession(config, wire)
    }

    internal fun socket(
        account: com.helix.app.account.AccountSession,
        endpoint: String,
    ) = account.speechSocket(endpoint)

    internal fun tts(account: com.helix.app.account.AccountSession): InterruptibleSpeechEngine =
        if (webSocket) {
            WebSocketTts(ttsEndpoint, socket(account, ttsEndpoint))
        } else {
            CloudSpeechEngine(HttpWavTts(session(ttsEndpoint, account)))
        }

    fun validate() {
        if (webSocket) {
            require(useAccountLogin)
            require(asrEndpoint == "wss://asr.sunlingzhang.com/ws/asr")
            require(ttsEndpoint == "wss://tts.sunlingzhang.com/ws/tts")
            return
        }
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

            rows.size == 6 && rows[0] == "account-v2" && rows[1] in setOf("true", "false") &&
                rows[4] in setOf("true", "false") && rows[5] in setOf("true", "false") -> {
                SpeechServiceSettings(
                    rows[1].toBooleanStrict(),
                    rows[2],
                    rows[3],
                    rows[4].toBooleanStrict(),
                    rows[5].toBooleanStrict(),
                )
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
                "account-v2",
                settings.httpEnabled.toString(),
                settings.asrEndpoint,
                settings.ttsEndpoint,
                settings.useAccountLogin.toString(),
                settings.webSocket.toString(),
            ),
        )
    }

    fun asrFactory(context: Context): StreamingAsrFactory {
        val settings = read()
        return if (settings.httpEnabled && settings.webSocket) {
            CloudAsrFactory(settings.asrEndpoint) {
                settings.validate()
                val account = (context.applicationContext as com.helix.app.HelixApplication).account
                WebSocketAsr(settings.asrEndpoint, settings.socket(account, settings.asrEndpoint))
            }
        } else if (settings.httpEnabled) {
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

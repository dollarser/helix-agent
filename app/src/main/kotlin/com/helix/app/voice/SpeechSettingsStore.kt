package com.helix.app.voice

import android.content.Context
import com.helix.app.account.AccountStatus
import com.helix.app.account.OfficialServices
import com.helix.app.account.speechSocket
import com.helix.app.internal.LineStore
import com.helix.app.internal.PrefsLineStore

data class SpeechServiceSettings(
    val httpEnabled: Boolean = false,
    val customAsrEndpoint: String = "",
    val customTtsEndpoint: String = "",
    val useAccountLogin: Boolean = false,
    val webSocket: Boolean = false,
) {
    val asrEndpoint get() = if (useAccountLogin) OfficialServices.asr(webSocket) else customAsrEndpoint
    val ttsEndpoint get() = if (useAccountLogin) OfficialServices.tts(webSocket) else customTtsEndpoint
    val requiresAccount: Boolean get() = httpEnabled && useAccountLogin

    fun session(
        endpoint: String,
        account: com.helix.app.account.AccountSession? = null,
        transport: com.helix.provider.api.wire.WireClient? = null,
    ): HttpSpeechSession {
        val parsed = HttpSpeechSession.config(endpoint)
        val config =
            if (useAccountLogin) {
                val canonical =
                    listOf(OfficialServices.asr(false), OfficialServices.tts(false)).firstOrNull {
                        HttpSpeechSession.config(it).endpoint == parsed.endpoint
                    }
                parsed.copy(endpoint = java.net.URI(requireNotNull(canonical)))
            } else {
                parsed
            }
        val target = config.endpoint.toString()
        val delegate =
            transport ?: com.helix.provider.api.wire.OkHttpWireClient(
                connectTimeoutMillis = 30000,
                readTimeoutMillis = config.timeoutMillis.toLong(),
                maxBodyBytes = 12_000_000,
                followRedirects = false,
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

    val automatic: Boolean get() = store.lines("settings").isEmpty()

    /** No manual selection: signed-in accounts use the official streaming service. Explicit offline stays offline. */
    fun forAccount(status: AccountStatus): SpeechServiceSettings =
        if (store.lines("settings").isEmpty() && status != AccountStatus.SIGNED_OUT) {
            SpeechServiceSettings(
                httpEnabled = true,
                useAccountLogin = true,
                webSocket = true,
            )
        } else {
            read()
        }

    fun read(): SpeechServiceSettings {
        val rows = store.lines("settings")
        return when {
            rows.isEmpty() -> {
                SpeechServiceSettings()
            }

            rows.size == 6 && rows[0] == "account-v1" && rows[1] in setOf("true", "false") &&
                rows[4] in setOf("true", "false") && rows[5] in setOf("true", "false") -> {
                SpeechServiceSettings(
                    rows[1].toBooleanStrict(),
                    rows[2],
                    rows[3],
                    rows[4].toBooleanStrict(),
                    rows[5].toBooleanStrict(),
                )
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
                settings.customAsrEndpoint,
                settings.customTtsEndpoint,
                settings.useAccountLogin.toString(),
                settings.webSocket.toString(),
            ),
        )
    }

    fun asrFactory(context: Context): StreamingAsrFactory {
        val application = context.applicationContext as com.helix.app.HelixApplication
        val settings = forAccount(application.account.status.value)
        val identity = application.account.identity

        fun verifyAccount() {
            if (settings.useAccountLogin) {
                check(application.account.signedIn.value && application.account.identity == identity)
            }
        }
        return if (settings.httpEnabled && settings.webSocket) {
            CloudAsrFactory(settings.asrEndpoint) {
                settings.validate()
                verifyAccount()
                val account = (context.applicationContext as com.helix.app.HelixApplication).account
                WebSocketAsr(settings.asrEndpoint, settings.socket(account, settings.asrEndpoint))
            }
        } else if (settings.httpEnabled) {
            CloudAsrFactory(settings.asrEndpoint, supportsPartial = false) {
                settings.validate()
                verifyAccount()
                HttpFileAsr(
                    settings.session(
                        settings.asrEndpoint,
                        (context.applicationContext as com.helix.app.HelixApplication).account,
                    ),
                )
            }
        } else {
            OfflineAsrFactory(context)
        }
    }
}

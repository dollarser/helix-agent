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
    val asrModel: String = "gpt-transcribe",
    val ttsModel: String = "gpt-realtime-2.1-mini",
    val providerId: String? = null,
) {
    val asrWebSocket get() = webSocket && asrModel != "gpt-transcribe"
    val ttsWebSocket get() = webSocket && ttsModel != "gpt-realtime-2.1-mini"
    val asrEndpoint get() = if (useAccountLogin) OfficialServices.asr(asrWebSocket) else customAsrEndpoint
    val ttsEndpoint get() = if (useAccountLogin) OfficialServices.tts(ttsWebSocket) else customTtsEndpoint
    val requiresAccount: Boolean get() = httpEnabled && useAccountLogin

    fun session(
        endpoint: String,
        account: com.helix.app.account.AccountSession? = null,
        transport: com.helix.provider.api.wire.WireClient? = null,
        configuredProviders: ConfiguredSpeechProviders? = null,
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
                maxBodyBytes = if (useAccountLogin || providerId != null) 48_000_000 else 12_000_000,
                followRedirects = false,
            )
        val wire =
            if (useAccountLogin) {
                val owner = requireNotNull(account)
                owner.wire(target, delegate)
            } else if (providerId != null) {
                requireNotNull(configuredProviders).wire(providerId, target, delegate)
            } else {
                delegate
            }
        val retryScope =
            when {
                useAccountLogin -> "account:${requireNotNull(account).identity}"
                providerId != null -> "provider:$providerId"
                else -> "anonymous"
            }
        return HttpSpeechSession(config, wire, retryScope)
    }

    internal fun socket(
        account: com.helix.app.account.AccountSession,
        endpoint: String,
    ) = account.speechSocket(endpoint)

    internal fun tts(
        account: com.helix.app.account.AccountSession,
        configuredProviders: ConfiguredSpeechProviders? = null,
    ): InterruptibleSpeechEngine =
        if (ttsWebSocket) {
            WebSocketTts(ttsEndpoint, socket(account, ttsEndpoint))
        } else {
            CloudSpeechEngine(
                if (useAccountLogin || providerId != null) {
                    HttpCloudTts(session(ttsEndpoint, account, configuredProviders = configuredProviders), ttsModel)
                } else {
                    HttpWavTts(session(ttsEndpoint, account))
                },
                maxDurationSeconds = if (useAccountLogin || providerId != null) 1200 else 120,
            )
        }

    fun validate() {
        validateSelection()
        validateEndpoint(asrEndpoint, asrWebSocket)
        validateEndpoint(ttsEndpoint, ttsWebSocket)
    }

    private fun validateSelection() {
        if (providerId != null) {
            require(!useAccountLogin && !webSocket)
            require(providerId.isNotBlank() && providerId.length <= 64 && providerId.none { it.isISOControl() })
        }
        for (model in listOf(asrModel, ttsModel)) {
            require(model.isNotBlank() && model.length <= 256 && model.none { it.isISOControl() })
        }
    }

    private fun validateEndpoint(
        endpoint: String,
        socket: Boolean,
    ) {
        if (socket) {
            require(useAccountLogin)
        } else {
            if (httpEnabled || endpoint.isNotBlank()) HttpSpeechSession.config(endpoint)
            if (useAccountLogin) {
                com.helix.app.account.AccountSession
                    .validateTarget(endpoint)
            }
        }
    }
}

/** Only the current explicit account-aware format is supported; retired public-ID settings are inert. */
class SpeechSettingsStore(
    private val store: LineStore,
    private val configuredProviders: ConfiguredSpeechProviders? = null,
) {
    constructor(context: Context, configuredProviders: ConfiguredSpeechProviders? = null) :
        this(PrefsLineStore(context, "speech-services", synchronous = true), configuredProviders)

    suspend fun availableProviders(): List<SpeechProviderOption> = configuredProviders?.available().orEmpty()

    fun tts(
        settings: SpeechServiceSettings,
        account: com.helix.app.account.AccountSession,
    ): InterruptibleSpeechEngine = settings.tts(account, configuredProviders)

    val automatic: Boolean get() = store.lines("settings").isEmpty()

    /** No manual selection: signed-in accounts use the official HTTP cloud service. Explicit offline stays offline. */
    fun forAccount(status: AccountStatus): SpeechServiceSettings =
        if (store.lines("settings").isEmpty() && status != AccountStatus.SIGNED_OUT) {
            SpeechServiceSettings(
                httpEnabled = true,
                useAccountLogin = true,
                webSocket = false,
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

            rows.size in setOf(6, 8, 9) && rows[0] == "account-v1" && rows[1] in setOf("true", "false") &&
                rows[4] in setOf("true", "false") && rows[5] in setOf("true", "false") -> {
                SpeechServiceSettings(
                    rows[1].toBooleanStrict(),
                    rows[2],
                    rows[3],
                    rows[4].toBooleanStrict(),
                    rows[5].toBooleanStrict(),
                    rows.getOrNull(6) ?: if (rows[5].toBooleanStrict()) "self-hosted" else "gpt-transcribe",
                    rows.getOrNull(7) ?: if (rows[5].toBooleanStrict()) "self-hosted" else "gpt-realtime-2.1-mini",
                    rows.getOrNull(8)?.takeIf { it.isNotBlank() },
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
                settings.asrModel,
                settings.ttsModel,
                settings.providerId.orEmpty(),
            ),
        )
    }

    fun asrFactory(
        account: com.helix.app.account.AccountSession,
        offlineFactory: () -> StreamingAsrFactory,
    ): StreamingAsrFactory {
        val settings = forAccount(account.status.value)
        val identity = account.identity

        fun verifyAccount() {
            if (settings.useAccountLogin) {
                check(account.signedIn.value && account.identity == identity)
            }
        }
        return if (settings.httpEnabled && settings.asrWebSocket) {
            CloudAsrFactory(settings.asrEndpoint) {
                settings.validate()
                verifyAccount()
                WebSocketAsr(settings.asrEndpoint, settings.socket(account, settings.asrEndpoint))
            }
        } else if (settings.httpEnabled) {
            CloudAsrFactory(settings.asrEndpoint, supportsPartial = false, maxDurationSeconds = 3600) {
                settings.validate()
                verifyAccount()
                HttpFileAsr(
                    settings.session(
                        settings.asrEndpoint,
                        account,
                        configuredProviders = configuredProviders,
                    ),
                    model = settings.asrModel.takeIf { settings.useAccountLogin || settings.providerId != null },
                )
            }
        } else {
            offlineFactory()
        }
    }
}

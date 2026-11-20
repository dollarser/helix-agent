package com.helix.app.account

import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.builtInConfig
import com.helix.app.voice.HttpCloudTts
import com.helix.app.voice.HttpFileAsr
import com.helix.app.voice.SpeechServiceSettings
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketMessage
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.openai.chat.OpenAiChatProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory ports only; all credentials/audio/text are synthetic and never leave this process. */
class OfficialGatewayContractTest {
    @Test fun accountModeDerivesUrlsAndCustomModeRetainsItsOwnAddresses() {
        val settings = SpeechServiceSettings(true, "https://custom.test/asr", "https://custom.test/tts", true, true)
        // Named cloud models override a stale socket toggle; self-hosted sockets remain explicit.
        assertEquals("https://api.hilex.me/v1/audio/transcriptions", settings.asrEndpoint)
        assertEquals("https://api.hilex.me/v1/audio/speech", settings.ttsEndpoint)
        val selfHosted = settings.copy(asrModel = "self-hosted", ttsModel = "self-hosted")
        selfHosted.validate()
        assertEquals("wss://api.hilex.me/ws/asr", selfHosted.asrEndpoint)
        assertEquals("wss://api.hilex.me/ws/tts", selfHosted.ttsEndpoint)
        assertEquals(
            "https://api.hilex.me/v1/audio/transcriptions",
            settings.copy(webSocket = false).asrEndpoint,
        )
        assertEquals("https://api.hilex.me/v1/audio/speech", settings.copy(webSocket = false).ttsEndpoint)
        val custom = settings.copy(useAccountLogin = false, webSocket = false)
        custom.validate()
        assertEquals("https://custom.test/asr", custom.asrEndpoint)
        assertEquals("https://custom.test/tts", custom.ttsEndpoint)
    }

    @Test fun loginRegisterAndRefreshStayOnAccountDomain() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val account =
                AccountSession(
                    MemoryAccountTokens(),
                    object : WireClient {
                        override suspend fun open(request: WireRequest) =
                            accountResponse(
                                200,
                                """
                                {"access_token":"synthetic-access","refresh_token":"synthetic-refresh",
                                 "expires_in":600,"refresh_expires_in":1200}
                                """.trimIndent(),
                            ).also { requests += request }
                    },
                    { 0L },
                )
            AccountProfileClient(account).register("fixture@example.test", "synthetic-password", "012345")
            account.login("fixture@example.test", "synthetic-password")
            account.authorization("synthetic-access", account.revision)
            assertEquals(
                listOf("register", "login", "refresh").map {
                    "https://accounts.hilex.me/auth/$it"
                },
                requests.map { it.url },
            )
            requests.forEach {
                assertEquals("POST", it.method)
                assertEquals("application/json", it.headers["Content-Type"])
                assertEquals(null, it.headers["X-Client-Id"])
            }
        }

    @Test fun officialFileSpeechUsesExactHttpPathsTypesAndAccountHeaders() {
        val requests = mutableListOf<WireRequest>()
        val settings = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true)
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): com.helix.provider.api.wire.WireResponse {
                    requests += request
                    if (request.url.endsWith(
                            "/transcriptions",
                        )
                    ) {
                        return accountResponse(200, """{"text":"synthetic transcript"}""")
                    }
                    return com.helix.provider.api.wire.WireResponse(
                        200,
                        emptyMap(),
                        object : com.helix.provider.api.wire.WireBody {
                            override suspend fun bytes() = byteArrayOf(0, 0)

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) =
                                onChunk(byteArrayOf(0, 0)).let { Unit }

                            override fun close() = Unit
                        },
                    )
                }
            }
        HttpFileAsr(settings.session(settings.asrEndpoint, testAccount(), wire), settings.asrModel).use {
            it.send(floatArrayOf(0f))
            assertEquals("synthetic transcript", it.finish())
        }
        HttpCloudTts(
            settings.session(settings.ttsEndpoint, testAccount(), wire),
            settings.ttsModel,
        ).use {
            it.stream("synthetic") { samples, rate ->
                assertEquals(24000, rate)
                assertEquals(1, samples.size)
            }
        }
        assertEquals(listOf(OfficialServices.asr(false), OfficialServices.tts(false)), requests.map { it.url })
        requests.forEach {
            assertEquals("POST", it.method)
            assertEquals("Bearer fixture-access", it.headers["Authorization"])
            assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
        }
        assertTrue(requests[0].headers.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
        assertTrue(requireNotNull(requests[0].body).toString(Charsets.ISO_8859_1).contains("name=\"file\""))
        assertEquals("application/json", requests[1].headers["Content-Type"])
        val requestJson =
            kotlinx.serialization.json.Json.parseToJsonElement(
                requireNotNull(requests[1].body).toString(Charsets.UTF_8),
            )
        assertEquals(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"model":"gpt-realtime-2.1-mini","input":"synthetic",
                    "voice":"marin","response_format":"pcm","speed":1,"stream":true}""",
            ),
            requestJson,
        )
        assertTrue(requireNotNull(requests[0].body).toString(Charsets.UTF_8).contains("gpt-transcribe"))
    }

    @Test fun catalogAndSseGenerationAppendPathsAfterExactlyOneV1() =
        runBlocking {
            val requests = mutableListOf<WireRequest>()
            val wire =
                object : WireClient {
                    override suspend fun open(request: WireRequest) =
                        accountResponse(
                            200,
                            if (request.method == "GET") {
                                """{"data":[{"id":"Qwen3.8-27B"}]}"""
                            } else {
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}," +
                                    "\"finish_reason\":\"stop\"}]}\n\n" +
                                    "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1," +
                                    "\"completion_tokens\":1}}\n\n" +
                                    "data: [DONE]\n\n"
                            },
                        ).also { requests += request }
                }
            val provider =
                com.helix.app.provider
                    .ProviderFactory(
                        { error("No custom credential") },
                        wire,
                        { error("No image") },
                        builtInWire = BuiltInAccountWire(testAccount(), wire),
                    ).create(builtInConfig())
            provider.listModels()
            val events =
                provider
                    .stream(
                        ModelRequest(BuiltInModel.MODEL_ID, listOf(ModelMessage(ModelRole.USER, "synthetic"))),
                    ).toList()
            assertTrue(events.any { it is ModelEvent.Completed })
            assertEquals(
                listOf(
                    "https://api.hilex.me/v1/models?output_modalities=all",
                    "https://api.hilex.me/v1/chat/completions",
                ),
                requests.map {
                    it.url
                },
            )
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            requests.forEach {
                assertEquals("Bearer fixture-access", it.headers["Authorization"])
                assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
            }
            assertEquals("application/json", requests[1].headers["Content-Type"])
            assertTrue(requireNotNull(requests[1].body).toString(Charsets.UTF_8).contains("\"stream\":true"))
        }

    @Test fun websocketPathsExcludeV1AndRetiredHostsCannotReceiveAccountTokens() {
        for (url in listOf(OfficialServices.asr(true), OfficialServices.tts(true))) {
            var connected = false
            testAccount()
                .speechSocket(
                    url,
                    SocketConnector { target, headers ->
                        assertEquals(url, target)
                        assertEquals("Bearer fixture-access", headers["Authorization"])
                        assertEquals(AccountSession.CLIENT_ID, headers["X-Client-ID"])
                        connected = true
                        object : SocketConnection {
                            override fun sendText(value: String) = Unit

                            override fun sendBinary(value: ByteArray) = Unit

                            override fun receive(timeoutMillis: Long): SocketMessage? = null

                            override fun close() = Unit
                        }
                    },
                ).connect(url, emptyMap())
                .close()
            assertTrue(connected)
        }
        for (url in listOf("wss://api.hilex.me/v1/ws/asr", "wss://api.sunlingzhang.com/ws/asr")) {
            assertThrows(IllegalArgumentException::class.java) { testAccount().speechSocket(url) }
        }
        for (host in listOf(
            "accounts.sunlingzhang.com",
            "api.sunlingzhang.com",
            "asr.sunlingzhang.com",
            "tts.sunlingzhang.com",
        )) {
            assertThrows(
                IllegalArgumentException::class.java,
            ) { AccountSession.validateTarget("https://$host/v1/audio/speech") }
        }
    }
}

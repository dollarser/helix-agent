package com.helix.app.account

import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.builtInConfig
import com.helix.app.voice.HttpFileAsr
import com.helix.app.voice.HttpWavTts
import com.helix.app.voice.SpeechServiceSettings
import com.helix.app.voice.SpeechWav
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
        assertEquals("wss://api.sunlingzhang.com/ws/asr", settings.asrEndpoint)
        assertEquals("wss://api.sunlingzhang.com/ws/tts", settings.ttsEndpoint)
        assertEquals(
            "https://api.sunlingzhang.com/v1/audio/transcriptions",
            settings.copy(webSocket = false).asrEndpoint,
        )
        assertEquals("https://api.sunlingzhang.com/v1/audio/speech", settings.copy(webSocket = false).ttsEndpoint)
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
            account.register("fixture@example.test", "synthetic-password")
            account.login("fixture@example.test", "synthetic-password")
            account.authorization("synthetic-access", account.revision)
            assertEquals(
                listOf("register", "login", "refresh").map {
                    "https://accounts.sunlingzhang.com/auth/$it"
                },
                requests.map { it.url },
            )
            requests.forEach {
                assertEquals("POST", it.method)
                assertEquals("application/json", it.headers["Content-Type"])
                assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
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
                            override suspend fun bytes() = SpeechWav.encode(byteArrayOf(0, 0), 16000)

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) =
                                error("File response only")

                            override fun close() = Unit
                        },
                    )
                }
            }
        HttpFileAsr(settings.session(settings.asrEndpoint, testAccount(), wire)).use {
            it.send(floatArrayOf(0f))
            assertEquals("synthetic transcript", it.finish())
        }
        HttpWavTts(
            settings.session(settings.ttsEndpoint, testAccount(), wire),
        ).use { it.stream("synthetic", { _, _ -> }) }
        assertEquals(listOf(OfficialServices.asr(false), OfficialServices.tts(false)), requests.map { it.url })
        requests.forEach {
            assertEquals("POST", it.method)
            assertEquals("Bearer fixture-access", it.headers["Authorization"])
            assertEquals(AccountSession.CLIENT_ID, it.headers["X-Client-Id"])
        }
        assertTrue(requests[0].headers.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
        assertTrue(requireNotNull(requests[0].body).toString(Charsets.ISO_8859_1).contains("name=\"file\""))
        assertEquals("application/json", requests[1].headers["Content-Type"])
        assertEquals("{\"input\":\"synthetic\"}", requireNotNull(requests[1].body).toString(Charsets.UTF_8))
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
                                    "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                            },
                        ).also { requests += request }
                }
            val provider =
                OpenAiChatProvider(builtInConfig(), {
                    error("No custom credential")
                }, BuiltInAccountWire(testAccount(), wire)) { error("No image") }
            provider.listModels()
            val events =
                provider
                    .stream(
                        ModelRequest(BuiltInModel.MODEL_ID, listOf(ModelMessage(ModelRole.USER, "synthetic"))),
                    ).toList()
            assertTrue(events.any { it is ModelEvent.Completed })
            assertEquals(
                listOf(
                    "https://api.sunlingzhang.com/v1/models",
                    "https://api.sunlingzhang.com/v1/chat/completions",
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
        assertThrows(IllegalArgumentException::class.java) {
            testAccount().speechSocket("wss://api.sunlingzhang.com/v1/ws/asr")
        }
        for (host in listOf("asr.sunlingzhang.com", "tts.sunlingzhang.com")) {
            assertThrows(
                IllegalArgumentException::class.java,
            ) { AccountSession.validateTarget("https://$host/v1/audio/speech") }
        }
    }
}

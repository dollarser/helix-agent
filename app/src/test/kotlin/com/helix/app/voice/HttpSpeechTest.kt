package com.helix.app.voice

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HttpSpeechTest {
    @Test fun serviceDeadlineIsAnIoFailureRatherThanAUserCancellation() {
        val config = CloudSpeechConfig(java.net.URI("https://example.test/speech"), HttpSpeechSession.PROTOCOL, 1000)
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse = awaitCancellation()
            }
        HttpSpeechSession(config, wire).use { session ->
            assertThrows(
                java.net.SocketTimeoutException::class.java,
            ) { session.post("application/json", byteArrayOf()) }
        }
    }

    @Test fun settingsRoundTripAndCorruptionNeverAuthorizeAnInferredService() {
        val lines =
            com.helix.app.internal
                .InMemoryLineStore()
        val store = SpeechSettingsStore(lines)
        assertEquals(SpeechServiceSettings(), store.read())
        val settings = SpeechServiceSettings(true, "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts")
        store.save(settings)
        assertEquals(settings, store.read())
        assertThrows(IllegalArgumentException::class.java) {
            store.save(settings.copy(customTtsEndpoint = "https://user:key@example.test/tts"))
        }
        assertEquals(settings, store.read())
        lines.setLines("settings", listOf("corrupt", settings.asrEndpoint, settings.ttsEndpoint))
        assertThrows(IllegalArgumentException::class.java) { store.read().validate() }
    }

    @Test fun enabledSettingsRequireTwoExplicitValidEndpoints() {
        SpeechServiceSettings().validate()
        assertThrows(IllegalArgumentException::class.java) {
            SpeechServiceSettings(httpEnabled = true).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechServiceSettings(true, "http://127.0.0.1:18767/asr", "https://user:key@example.test/tts").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechServiceSettings(false, "", "https://user:key@example.test/tts").validate()
        }
        SpeechServiceSettings(true, "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts").validate()
    }

    private class Wire(
        private val payload: ByteArray,
        private val status: Int = 200,
    ) : WireClient {
        var request: WireRequest? = null
        var closed = false

        override suspend fun open(request: WireRequest): WireResponse {
            this.request = request
            return WireResponse(
                status,
                emptyMap(),
                object : WireBody {
                    override suspend fun bytes() = payload

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                        onChunk(payload)
                    }

                    override fun close() {
                        closed = true
                    }
                },
            )
        }
    }

    @Test fun fileAsrUploadsOneWavOnlyAtFinishAndRequiresStringText() {
        val wire = Wire("{\"text\":\"最终转写\",\"language\":\"zh\"}".toByteArray())
        val config = HttpSpeechSession.config("http://127.0.0.1:12345/v1/audio/transcriptions")
        HttpFileAsr(HttpSpeechSession(config, wire)).use {
            assertEquals("", it.send(floatArrayOf(0f, 0.5f)))
            assertEquals(null, wire.request)
            assertEquals("最终转写", it.finish())
        }
        val request = requireNotNull(wire.request)
        assertTrue(requireNotNull(request.headers["Content-Type"]).startsWith("multipart/form-data; boundary="))
        val body = requireNotNull(request.body)
        assertTrue(body.toString(Charsets.ISO_8859_1).contains("name=\"file\"; filename=\"capture.wav\""))
        assertTrue(body.toString(Charsets.ISO_8859_1).contains("RIFF"))
        assertTrue(wire.closed)
        val invalid = Wire("{\"text\":123}".toByteArray())
        HttpFileAsr(HttpSpeechSession(config, invalid)).use {
            it.send(floatArrayOf(0f))
            assertThrows(IllegalArgumentException::class.java) { it.finish() }
        }
    }

    @Test fun wavTtsSubmitsJsonAndDecodesMonoPcm16() {
        val pcm = byteArrayOf(0, 0, 0, 64, 0, -128)
        val wire = Wire(SpeechWav.encode(pcm, 24000))
        val config = HttpSpeechSession.config("http://127.0.0.1:12345/v1/audio/speech")
        val received = mutableListOf<Float>()
        HttpWavTts(HttpSpeechSession(config, wire)).use {
            it.stream("你好") { samples, rate ->
                assertEquals(24000, rate)
                received.addAll(samples.toList())
            }
        }
        assertEquals(listOf(0f, 0.5f, -1f), received)
        assertEquals("{\"input\":\"你好\"}", requireNotNull(wire.request?.body).toString(Charsets.UTF_8))
        assertTrue(wire.closed)
    }

    @Test fun accountModeRequiresExplicitTrustedHttpsAndRetiredPublicIdSettingsAreRejected() {
        val lines =
            com.helix.app.internal
                .InMemoryLineStore()
        val store = SpeechSettingsStore(lines)
        lines.setLines("settings", listOf("true", "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts", "true"))
        lines.setLines("settings", listOf("true", "http://127.0.0.1:18767/asr", "http://127.0.0.1:18768/tts"))
        assertEquals("", store.read().asrEndpoint)
        assertEquals("", store.read().ttsEndpoint)
        assertTrue(!store.read().useAccountLogin)
        val settings =
            SpeechServiceSettings(
                true,
                "https://api.sunlingzhang.com/v1/audio/transcriptions",
                "https://api.sunlingzhang.com/v1/audio/speech",
                true,
            )
        store.save(settings)
        assertEquals(settings, store.read())
        assertEquals(
            "https://api.sunlingzhang.com/v1/audio/speech",
            settings.copy(customTtsEndpoint = "https://evil.test/speech").ttsEndpoint,
        )
        assertThrows(IllegalArgumentException::class.java) {
            settings.session(
                "https://evil.test/speech",
                com.helix.app.account
                    .testAccount(),
            )
        }
    }

    @Test fun accountSpeechPinsTheSameCanonicalTargetAndBothHeaders() {
        val account =
            com.helix.app.account
                .testAccount()
        val settings = SpeechServiceSettings(useAccountLogin = true)
        for (endpoint in listOf(
            "https://api.sunlingzhang.com/v1/audio/speech",
            "https://api.sunlingzhang.com:443/v1/audio/speech",
            "https://api.sunlingzhang.com/v1/audio/transcriptions",
        )) {
            var calls = 0
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        calls++
                        assertEquals(endpoint.replace(":443", ""), request.url)
                        assertEquals("Bearer fixture-access", request.headers["Authorization"])
                        assertEquals(com.helix.app.account.AccountSession.CLIENT_ID, request.headers["X-Client-Id"])
                        return com.helix.app.account
                            .accountResponse(200, "fixture")
                    }
                }
            settings.session(endpoint, account, transport).use {
                assertEquals("fixture", it.post("application/json", "{}".toByteArray()).toString(Charsets.UTF_8))
            }
            assertEquals(1, calls)
        }
    }

    @Test fun invalidWavOrHttpFailureNeverBecomesSuccessfulAudio() {
        val config = HttpSpeechSession.config("http://127.0.0.1:12345/v1/audio/speech")
        val invalid = SpeechWav.encode(byteArrayOf(0, 0), 16000).also { it[22] = 2 }
        HttpWavTts(HttpSpeechSession(config, Wire(invalid))).use {
            assertThrows(IllegalArgumentException::class.java) { it.stream("test") { _, _ -> error("invalid audio") } }
        }
        val failed = Wire("private error details".toByteArray(), 503)
        HttpWavTts(HttpSpeechSession(config, failed)).use {
            val error = assertThrows(IllegalStateException::class.java) { it.stream("test") { _, _ -> } }
            assertEquals("SPEECH_HTTP_503", error.message)
        }
        assertTrue(failed.closed)
    }

    @Test fun closeCancelsPendingHttpHeadersWithoutWaitingForTimeout() {
        val entered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val session =
            HttpSpeechSession(
                HttpSpeechSession.config("http://127.0.0.1:12345/speech"),
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        entered.countDown()
                        awaitCancellation()
                    }
                },
            )
        val worker =
            thread {
                try {
                    session.post("application/json", "{}".toByteArray())
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            session.close()
            worker.join(2000)
            assertTrue(!worker.isAlive)
            assertTrue(failure.get() is kotlinx.coroutines.CancellationException)
        } finally {
            session.close()
            worker.join(2000)
        }
    }

    @Test fun configuredHostServiceRoundTripUsesOnlyGeneratedSpeech() {
        val asrUrl = System.getenv("HELIX_SPEECH_HOST_ASR_URL")
        val ttsUrl = System.getenv("HELIX_SPEECH_HOST_TTS_URL")
        assumeTrue("Optional explicit host-only service profile is not supplied", asrUrl != null || ttsUrl != null)
        require(!asrUrl.isNullOrBlank() && !ttsUrl.isNullOrBlank())
        val text = "你好，这是语音测试。"
        val synthesized = mutableListOf<Float>()
        HttpWavTts(HttpSpeechSession(HttpSpeechSession.config(ttsUrl))).use {
            it.stream(text) { samples, rate ->
                require(rate == 24000)
                synthesized.addAll(samples.toList())
            }
        }
        require(synthesized.isNotEmpty())
        // Globally indexed interpolation: packet boundaries must not reset the resampling phase.
        val samples =
            FloatArray(synthesized.size * 2 / 3) { index ->
                val position = index * 1.5
                val first = position.toInt()
                val second = minOf(first + 1, synthesized.lastIndex)
                val fraction = (position - first).toFloat()
                synthesized[first] * (1 - fraction) + synthesized[second] * fraction
            }
        val result =
            HttpFileAsr(HttpSpeechSession(HttpSpeechSession.config(asrUrl))).use {
                samples.asList().chunked(1600).forEach { part -> it.send(part.toFloatArray()) }
                it.finish()
            }
        assertEquals(text.filter { it.isLetterOrDigit() }, result.filter { it.isLetterOrDigit() })
    }
}

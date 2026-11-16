package com.helix.app.voice

import com.helix.app.account.accountResponse
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudHttpSpeechContractTest {
    private class Wire(
        private val response: (Int) -> WireResponse,
    ) : WireClient {
        val requests = mutableListOf<WireRequest>()

        override suspend fun open(request: WireRequest): WireResponse {
            requests.add(request)
            return response(requests.size)
        }
    }

    @Test fun cloudModelsNeverUseLegacySocketsEvenWithOldSocketToggle() {
        val cloud = SpeechServiceSettings(httpEnabled = true, useAccountLogin = true, webSocket = true)
        cloud.validate()
        assertFalse(cloud.asrWebSocket)
        assertFalse(cloud.ttsWebSocket)
        assertEquals("https://api.hilex.me/v1/audio/transcriptions", cloud.asrEndpoint)
        assertEquals("https://api.hilex.me/v1/audio/speech", cloud.ttsEndpoint)
        val local = cloud.copy(asrModel = "self-hosted", ttsModel = "self-hosted")
        local.validate()
        assertTrue(local.asrWebSocket && local.ttsWebSocket)
        assertEquals("wss://api.hilex.me/ws/asr", local.asrEndpoint)
    }

    @Test fun cloudAsrSendsModelAndActualWavAndNeverReplaysFailedFinish() {
        val wire = Wire { accountResponse(429, "{\"detail\":\"quota exhausted\"}") }
        HttpFileAsr(
            HttpSpeechSession(HttpSpeechSession.config("https://example.test/asr"), wire),
            "gpt-transcribe",
        ).use {
            it.send(floatArrayOf(0f))
            val failure = assertThrows(SpeechHttpFailure::class.java) { it.finish() }
            assertEquals("quota exhausted", failure.details.detail)
            assertThrows(IllegalStateException::class.java) { it.finish() }
        }
        assertEquals(1, wire.requests.size)
        val body = requireNotNull(wire.requests.single().body).toString(Charsets.ISO_8859_1)
        assertTrue(body.contains("name=\"model\"\r\n\r\ngpt-transcribe"))
        assertTrue(body.contains("name=\"response_format\"\r\n\r\njson"))
        assertTrue(body.contains("RIFF"))
    }

    @Test fun asrSplitsAtFiveMinutesAndJoinsOnlySuccessfulSegments() {
        val wire = Wire { accountResponse(200, "{\"text\":\"segment$it\"}") }
        HttpFileAsr(
            HttpSpeechSession(HttpSpeechSession.config("https://example.test/asr"), wire),
            "gpt-transcribe",
        ).use {
            repeat(300) { _ -> it.send(FloatArray(16000)) }
            assertEquals(0, wire.requests.size)
            assertEquals("segment1", it.send(floatArrayOf(0f)))
            assertEquals("segment1\nsegment2", it.finish())
        }
        assertEquals(2, wire.requests.size)
        assertTrue(wire.requests.all { requireNotNull(it.body).size < 21 * 1024 * 1024 })
    }

    @Test fun plainTextTranscriptionUsesExplicitTextResponseFormat() {
        val wire = Wire { accountResponse(200, "你好") }
        HttpFileAsr(
            HttpSpeechSession(HttpSpeechSession.config("https://example.test/asr"), wire),
            "gpt-transcribe",
            "text",
        ).use {
            it.send(floatArrayOf(0f))
            assertEquals("你好", it.finish())
        }
    }

    @Test fun unicodeSegmentationPreservesAllCodePointsIncludingSpaces() {
        val text = "😀".repeat(1999) + " .后"
        val chunks = speechCodePointChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertEquals(listOf(2000, 2), chunks.map { it.codePointCount(0, it.length) })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun ttsUsesModelMarinAndExplicitPcmAt24Khz() {
        val wire = Wire { accountResponse(200, "\u0000\u0000\u0000@") }
        val received = mutableListOf<Float>()
        HttpCloudTts(HttpSpeechSession(HttpSpeechSession.config("https://example.test/tts"), wire)).use {
            it.stream("😀".repeat(2001)) { samples, rate ->
                assertEquals(24000, rate)
                received.addAll(samples.toList())
            }
        }
        assertEquals(2, wire.requests.size)
        assertEquals(listOf(0f, 0.5f, 0f, 0.5f), received)
        val body =
            Json
                .parseToJsonElement(
                    requireNotNull(wire.requests.first().body).toString(Charsets.UTF_8),
                ).jsonObject
        assertEquals("gpt-realtime-2.1-mini", body.getValue("model").jsonPrimitive.content)
        assertEquals("marin", body.getValue("voice").jsonPrimitive.content)
        assertEquals("pcm", body.getValue("response_format").jsonPrimitive.content)
        assertEquals("1", body.getValue("speed").jsonPrimitive.content)
    }

    @Test fun invalidCloudSpeechParametersFailWithoutAudioOrAutomaticResubmission() {
        val wire = Wire { accountResponse(422, "{\"detail\":\"unsupported format\"}") }
        val session = HttpSpeechSession(HttpSpeechSession.config("https://example.test/tts-invalid"), wire)
        CloudSpeechEngine(HttpCloudTts(session)).use { engine ->
            val error =
                assertThrows(SpeechHttpFailure::class.java) {
                    engine.generate("synthetic text", { true }) { _, _ -> error("No fallback audio is allowed") }
                }
            assertEquals(SpeechFailure.INVALID_REQUEST, speechFailure(error))
            assertEquals("unsupported format", error.details.detail)
        }
        assertEquals(1, wire.requests.size)
    }
}

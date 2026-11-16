package com.helix.app.voice

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HttpSpeechStreamingTest {
    private class Wire(
        val headers: Map<String, List<String>> = emptyMap(),
        val chunks: suspend (suspend (ByteArray) -> Boolean) -> Unit,
    ) : WireClient {
        var closed = false
        val requests = mutableListOf<WireRequest>()

        override suspend fun open(request: WireRequest): WireResponse {
            requests += request
            return WireResponse(
                200,
                headers,
                object : WireBody {
                    override suspend fun bytes(): ByteArray = error("Streaming must not buffer the entire response")

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) = chunks(onChunk)

                    override fun close() {
                        closed = true
                    }
                },
            )
        }
    }

    private fun session(wire: WireClient): HttpSpeechSession {
        val config = HttpSpeechSession.config("https://stream.test/speech")
        return HttpSpeechSession(config, wire)
    }

    @Test fun firstSamplesArriveBeforeEofAndOddChunksPreserveSamples() {
        val received = mutableListOf<Float>()
        var eof = false
        val wire =
            Wire { chunk ->
                chunk(byteArrayOf(0))
                assertTrue(received.isEmpty())
                chunk(byteArrayOf(64, 0))
                assertEquals(listOf(0.5f), received)
                chunk(byteArrayOf(-128))
                eof = true
            }
        HttpCloudTts(session(wire)).use { tts ->
            tts.stream("synthetic") { samples, rate ->
                assertFalse(eof)
                assertEquals(24000, rate)
                received += samples.toList()
            }
        }
        assertEquals(listOf(0.5f, -1f), received)
        assertTrue(wire.closed)
        assertTrue(requireNotNull(wire.requests.single().body).toString(Charsets.UTF_8).contains("\"stream\":true"))
    }

    @Test fun danglingHalfSampleIsAProtocolFailureWithoutReplay() {
        val wire =
            Wire {
                it(byteArrayOf(0, 64, 1))
                Unit
            }
        HttpCloudTts(session(wire)).use { tts ->
            assertThrows(IllegalArgumentException::class.java) { tts.stream("synthetic") { _, _ -> } }
        }
        assertEquals(1, wire.requests.size)
        assertTrue(wire.closed)
    }

    @Test fun cancellationClosesBodyAndDoesNotStartTheNextTextSegment() {
        val wire =
            Wire(mapOf("X-Request-ID" to listOf("fixture-cancelled-id"))) {
                it(byteArrayOf(0, 64))
                error("Cancellation must stop the response callback")
            }
        val tts = HttpCloudTts(session(wire))
        assertThrows(CancellationException::class.java) {
            tts.stream("字".repeat(2001)) { _, _ ->
                tts.close()
                assertTrue(wire.closed)
            }
        }
        assertEquals(1, wire.requests.size)
    }

    @Test fun failureAfterAudioDoesNotReplayOrStartAnotherSegment() {
        var frames = 0
        val wire =
            Wire {
                it(byteArrayOf(0, 64))
                throw IOException("synthetic disconnect")
            }
        HttpCloudTts(session(wire)).use { tts ->
            assertThrows(IOException::class.java) {
                tts.stream("字".repeat(2001)) { samples, _ -> frames += samples.size }
            }
        }
        assertEquals(1, frames)
        assertEquals(1, wire.requests.size)
        assertTrue(wire.closed)
    }

    @Test fun streamedBodyHasAnIndependentBoundEvenForCustomTransports() {
        val wire = Wire { chunk -> repeat(800) { chunk(ByteArray(65536)) } }
        session(wire).use { http ->
            assertThrows(IllegalArgumentException::class.java) {
                http.postStream("application/json", byteArrayOf()) { }
            }
        }
        assertTrue(wire.closed)
    }

    @Test fun closeInterruptsAReadWaitingAfterTheFirstAudioChunk() {
        val waiting = java.util.concurrent.CountDownLatch(1)
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
        val wire =
            Wire { chunk ->
                chunk(byteArrayOf(0, 64))
                waiting.countDown()
                kotlinx.coroutines.awaitCancellation()
            }
        val tts = HttpCloudTts(session(wire))
        val worker =
            kotlin.concurrent.thread {
                try {
                    tts.stream("字".repeat(2001)) { _, _ -> }
                } catch (error: CancellationException) {
                    failure.set(error)
                }
            }
        try {
            assertTrue(waiting.await(2, java.util.concurrent.TimeUnit.SECONDS))
            tts.close()
            worker.join(2000)
            assertFalse(worker.isAlive)
            assertTrue(wire.closed)
            assertTrue(failure.get() is CancellationException)
            assertEquals(1, wire.requests.size)
        } finally {
            tts.close()
            worker.join(2000)
        }
    }

    @Test fun unexpectedStreamSampleRateFailsBeforePlayback() {
        var heard = false
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse =
                    WireResponse(
                        200,
                        mapOf("X-Audio-Sample-Rate" to listOf("16000")),
                        object : WireBody {
                            override suspend fun bytes(): ByteArray = error("No buffering")

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(byteArrayOf(0, 0))
                            }

                            override fun close() = Unit
                        },
                    )
            }
        HttpCloudTts(session(wire)).use { tts ->
            assertThrows(IllegalArgumentException::class.java) { tts.stream("synthetic") { _, _ -> heard = true } }
        }
        assertFalse(heard)
    }

    @Test fun unconfirmedThirdPartyModelKeepsBufferedContractWithoutStreamFlag() {
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    assertFalse(requireNotNull(request.body).toString(Charsets.UTF_8).contains("\"stream\""))
                    return WireResponse(
                        200,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes() = byteArrayOf(0, 64)

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                error("Must stay buffered")
                            }

                            override fun close() = Unit
                        },
                    )
                }
            }
        var sample: Float? = null
        HttpCloudTts(session(wire), model = "third-party-voice").use { tts ->
            tts.stream("synthetic") { pcm, _ -> sample = pcm.single() }
        }
        assertEquals(0.5f, sample)
    }

    @Test fun responseRequestIdSurvivesNetworkAndReadTimeoutAfterAudio() {
        val failures = listOf(IOException("synthetic disconnect"), java.net.SocketTimeoutException("synthetic timeout"))
        for (cause in failures) {
            val wire =
                Wire(mapOf("X-Request-ID" to listOf("fixture-stream-id"))) { chunk ->
                    chunk(byteArrayOf(0, 64))
                    throw cause
                }
            var frames = 0
            HttpCloudTts(session(wire)).use { tts ->
                val error =
                    assertThrows(SpeechTransportFailure::class.java) {
                        tts.stream("synthetic") { samples, _ -> frames += samples.size }
                    }
                assertEquals(speechFailure(cause), speechFailure(error))
                assertEquals("fixture-stream-id", speechFailureDetails(error)?.requestId)
                assertEquals(null, speechFailureDetails(error)?.detail)
            }
            assertEquals(1, frames)
            assertEquals(1, wire.requests.size)
            assertTrue(wire.closed)
        }
    }

    @Test fun streamDeadlineRetainsResponseIdWithoutChangingTimeoutCategory() {
        val wire =
            Wire(mapOf("X-Request-ID" to listOf("fixture-deadline-id"))) { chunk ->
                chunk(byteArrayOf(0, 64))
                kotlinx.coroutines.awaitCancellation()
            }
        val config = HttpSpeechSession.config("https://stream.test/deadline").copy(timeoutMillis = 1000)
        HttpCloudTts(HttpSpeechSession(config, wire)).use { tts ->
            val error = assertThrows(SpeechTransportFailure::class.java) { tts.stream("synthetic") { _, _ -> } }
            assertEquals(SpeechFailure.TIMEOUT, speechFailure(error))
            assertEquals("fixture-deadline-id", speechFailureDetails(error)?.requestId)
        }
        assertEquals(1, wire.requests.size)
        assertTrue(wire.closed)
    }
}

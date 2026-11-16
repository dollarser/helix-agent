package com.helix.app.voice

import com.helix.provider.api.wire.WireBody
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic HTTP chunks through the production decoder/engine and a muted emulator AudioTrack. */
class HttpSpeechStreamingDeviceTest {
    @Test fun androidPipelineConsumesPcmBeforeSyntheticResponseCompletes() {
        var completed = false
        var closed = false
        val heard = mutableListOf<Float>()
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse =
                    WireResponse(
                        200,
                        emptyMap(),
                        object : WireBody {
                            override suspend fun bytes(): ByteArray = error("Must use streaming")

                            override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                                onChunk(byteArrayOf(0))
                                assertTrue(heard.isEmpty())
                                onChunk(byteArrayOf(64, 0))
                                assertEquals(listOf(0.5f), heard)
                                onChunk(byteArrayOf(-128))
                                completed = true
                            }

                            override fun close() {
                                closed = true
                            }
                        },
                    )
            }
        val session = HttpSpeechSession(HttpSpeechSession.config("https://synthetic.invalid/speech"), wire)
        CloudSpeechEngine(HttpCloudTts(session)).use { engine ->
            engine.generate("合成分块测试", { true }) { samples, rate ->
                assertFalse(completed)
                assertEquals(24000, rate)
                heard += samples.toList()
            }
        }
        assertTrue(closed)
        assertEquals(listOf(0.5f, -1f), heard)
    }

    @Test fun silentAudioTrackDrainsFirstSegmentBeforeResponseEnds() {
        check(
            android.os.Build.MODEL
                .contains("sdk_gphone"),
        )
        val context =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
                .targetContext
        var complete = false
        var consumed = 0
        SpeechAudio(context, volume = 0f).sink(24000).use { sink ->
            val body =
                object : WireBody {
                    override suspend fun bytes(): ByteArray = error("Must use streaming")

                    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                        onChunk(ByteArray(9600))
                        assertEquals(4800, consumed)
                        sink.finish { true }
                        assertFalse(complete)
                        onChunk(ByteArray(9600))
                        complete = true
                    }

                    override fun close() = Unit
                }
            val wire =
                object : WireClient {
                    override suspend fun open(request: WireRequest) = WireResponse(200, emptyMap(), body)
                }
            val config = HttpSpeechSession.config("https://synthetic.invalid/speech")
            CloudSpeechEngine(HttpCloudTts(HttpSpeechSession(config, wire))).use { engine ->
                engine.generate("静音播放链路测试", { true }) { samples, rate ->
                    assertFalse(complete)
                    assertEquals(24000, rate)
                    sink.write(samples) { true }
                    consumed += samples.size
                }
            }
            sink.finish { true }
            assertEquals(9600, consumed)
            assertTrue(complete)
        }
    }
}

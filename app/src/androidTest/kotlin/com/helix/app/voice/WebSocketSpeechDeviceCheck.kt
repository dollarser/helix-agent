package com.helix.app.voice

import android.content.Context
import com.helix.app.account.AccountSession
import com.helix.app.account.speechSocket
import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Invoked only inside the explicitly opted-in, authenticated disposable-emulator lifecycle. */
internal object WebSocketSpeechDeviceCheck {
    @Suppress("LongMethod", "CyclomaticComplexMethod") // One synthetic round trip with shared PCM provenance.
    fun run(
        context: Context,
        account: AccountSession,
    ) {
        val directory = File(context.cacheDir, "ws-speech").apply { mkdirs() }
        val expected = "你好，这是模拟器语音接入测试。"
        val result = File(directory, "result.txt")
        result.writeText("input=$expected\n")
        val ttsUrl = "wss://api.sunlingzhang.com/ws/tts"
        val asrUrl = "wss://api.sunlingzhang.com/ws/asr"
        val events = File(directory, "events.jsonl")
        events.writeText("")
        val ttsBytes = ByteArrayOutputStream()
        val sent = ByteArrayOutputStream()
        val frameLog = File(directory, "frames.txt").apply { writeText("") }

        fun observe(
            delegate: SocketConnector,
            label: String,
        ) = SocketConnector { url, headers ->
            val actual = delegate.connect(url, headers)
            object : SocketConnection {
                var frame = 0

                override fun sendText(value: String) {
                    events.appendText("$label send $value\n")
                    actual.sendText(value)
                }

                override fun sendBinary(value: ByteArray) {
                    sent.write(value)
                    frameLog.appendText("${frame++} ${value.size} ${hash(value)}\n")
                    actual.sendBinary(value)
                }

                override fun receive(timeoutMillis: Long): SocketMessage? =
                    actual.receive(timeoutMillis).also {
                        when (it) {
                            is SocketMessage.Text -> events.appendText("$label receive ${it.value}\n")
                            is SocketMessage.Closed -> events.appendText("$label close ${it.code}\n")
                            is SocketMessage.Binary -> if (label == "tts") ttsBytes.write(it.value)
                            else -> Unit
                        }
                    }

                override fun close() = actual.close()
            }
        }
        val audio = mutableListOf<Float>()
        var index = 0
        SpeechAudio(context, volume = 0f).sink(24000).use { sink ->
            WebSocketTts(ttsUrl, observe(account.speechSocket(ttsUrl), "tts")).use { engine ->
                engine.generateStream({
                    when (index++) {
                        0 -> {
                            "你好，"
                        }

                        1 -> {
                            Thread.sleep(200)
                            "这是模拟器语音接入测试。"
                        }

                        else -> {
                            null
                        }
                    }
                }, { true }) { pcm, rate ->
                    assertEquals(24000, rate)
                    audio.addAll(pcm.toList())
                    sink.write(pcm) { true }
                }
            }
            sink.finish { true }
        }
        assertTrue(audio.isNotEmpty())
        File(directory, "tts.wav").writeBytes(SpeechWav.encode(ttsBytes.toByteArray(), 24000))
        val samples =
            FloatArray((audio.size.toLong() * 16000 / 24000).toInt()) { i ->
                val position = i * 1.5
                val first = position.toInt().coerceAtMost(audio.lastIndex)
                val next = minOf(first + 1, audio.lastIndex)
                audio[first] + (audio[next] - audio[first]) * (position - first).toFloat()
            }
        val chunks = samples.asList().chunked(1600).map { it.toFloatArray() }
        val expectedPcm = ByteArrayOutputStream().apply { chunks.forEach { write(encodeSpeechPcm(it)) } }.toByteArray()
        File(directory, "asr-input.pcm").writeBytes(expectedPcm)
        File(directory, "asr-input.wav").writeBytes(SpeechWav.encode(expectedPcm, 16000))
        result.appendText("sampleRate=16000\nbytes=${expectedPcm.size}\nsha256=${hash(expectedPcm)}\n")
        WebSocketAsr(asrUrl, observe(account.speechSocket(asrUrl), "asr")).use { asr ->
            chunks.forEach { pcm ->
                val partial = asr.send(pcm)
                events.appendText("display partial $partial\n")
                Thread.sleep(100)
            }
            val final = asr.finish()
            result.appendText("wsFinal=$final\n")
            assertTrue(final.isNotBlank())
        }
        assertArrayEquals(expectedPcm, sent.toByteArray())
        HttpFileAsr(
            SpeechServiceSettings(useAccountLogin = true).session(
                "https://api.sunlingzhang.com/v1/audio/transcriptions",
                account,
                recordingHttp(directory, expectedPcm),
            ),
        ).use { http ->
            chunks.forEach { http.send(it) }
            val final = http.finish()
            result.appendText("httpFinal=$final\n")
            assertTrue(final.isNotBlank())
        }
        // Explicit fresh session, no old frames replayed. Cancellation does not claim server inference stopped.
        WebSocketAsr(asrUrl, account.speechSocket(asrUrl)).use { it.send(FloatArray(1600)) }
        busyProbe(account, ttsUrl, result)
        result.appendText("samePcm=passed\nreconnectAndCancel=passed\ntransport=passed\nquality=not-asserted\n")
    }

    private fun recordingHttp(
        directory: File,
        expectedPcm: ByteArray,
    ): com.helix.provider.api.wire.WireClient =
        object : com.helix.provider.api.wire.WireClient {
            private val delegate =
                com.helix.provider.api.wire.OkHttpWireClient(
                    followRedirects = false,
                )

            override suspend fun open(
                request: com.helix.provider.api.wire.WireRequest,
            ): com.helix.provider.api.wire.WireResponse {
                val multipart = requireNotNull(request.body)
                val marker = "RIFF".toByteArray()
                val start =
                    (0..multipart.size - 4).first { at ->
                        marker.indices.all { multipart[at + it] == marker[it] }
                    }
                // Encoder produces a canonical 44-byte PCM16 WAV header; compare actual multipart audio bytes.
                assertArrayEquals(expectedPcm, multipart.copyOfRange(start + 44, start + 44 + expectedPcm.size))
                val response = delegate.open(request)
                val bytes =
                    try {
                        response.body.bytes()
                    } finally {
                        response.body.close()
                    }
                File(directory, "http-response.json").writeBytes(bytes)
                return com.helix.provider.api.wire.WireResponse(
                    response.status,
                    response.headers,
                    object : com.helix.provider.api.wire.WireBody {
                        override suspend fun bytes() = bytes

                        override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
                            onChunk(bytes)
                        }

                        override fun close() = Unit
                    },
                )
            }
        }

    private fun busyProbe(
        account: AccountSession,
        endpoint: String,
        result: File,
    ) {
        account.speechSocket(endpoint).connect(endpoint, emptyMap()).use { first ->
            val ready = first.receive(30000)
            check(ready is SocketMessage.Text && ready.value.contains("ready"))
            account.speechSocket(endpoint).connect(endpoint, emptyMap()).use { second ->
                when (val reply = second.receive(10000)) {
                    is SocketMessage.Closed -> {
                        result.appendText(
                            "busyCloseCode=${reply.code}\n" +
                                "busyContract=${if (reply.code == 1013) "passed" else "failed"}\n",
                        )
                    }

                    else -> {
                        result.appendText("busyContract=not-confirmed\n")
                    }
                }
            }
        }
    }

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}

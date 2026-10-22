package com.helix.app.voice

import com.helix.provider.api.wire.SocketConnection
import com.helix.provider.api.wire.SocketConnector
import com.helix.provider.api.wire.SocketMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class SpeechSocketFailure(
    val closeCode: Int,
) : IOException(
        if (closeCode == 1013) "Speech model busy; retry later" else "Speech socket closed: $closeCode",
    )

/** Shared encoder: one ordered frame is sent once, without a container header. */
internal fun encodeSpeechPcm(samples: FloatArray): ByteArray {
    require(samples.size in 1..16000 && samples.all { it.isFinite() && it in -1f..1f })
    return ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, sample ->
            val value = (sample * 32767).toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}

private fun event(message: SocketMessage): JsonObject =
    when (message) {
        is SocketMessage.Text -> Json.parseToJsonElement(message.value).jsonObject
        is SocketMessage.Closed -> throw SpeechSocketFailure(message.code)
        is SocketMessage.Binary -> throw IOException("Unexpected speech audio")
    }

private fun JsonObject.text(key: String): String? {
    val value = get(key) ?: return null
    val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: throw IOException("Invalid speech field")
    require(primitive.isString) { "Invalid speech string" }
    return primitive.content
}

private fun SocketConnection.ready(rate: Int) {
    val value = event(receive(30000) ?: throw IOException("Speech ready timeout"))
    require(
        value.text("type") == "ready" && value["sample_rate"]?.jsonPrimitive?.intOrNull == rate &&
            value.text("format") == "s16le" && value["channels"]?.jsonPrimitive?.intOrNull == 1,
    ) { "Unsupported speech format" }
}

private fun SocketConnection.end() = sendText("{\"type\":\"end\"}")

@Suppress("TooGenericExceptionCaught") // Any handshake/parser failure must release the socket.
internal class WebSocketAsr(
    endpoint: String,
    connector: SocketConnector,
    private val nanoTime: () -> Long = System::nanoTime,
) : CloudAsrConnection {
    private val socket = connector.connect(endpoint, emptyMap())
    private val closed = AtomicBoolean()
    private var ended = false
    private var final = false
    private var transcript = ""

    init {
        try {
            socket.ready(16000)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    override fun send(samples: FloatArray): String {
        check(!closed.get() && !ended && !final)
        socket.sendBinary(encodeSpeechPcm(samples))
        while (true) {
            val next = socket.receive(0) ?: break
            accept(next)
            if (final) throw IOException("ASR finalized before end")
        }
        check(!closed.get())
        return transcript
    }

    @Suppress("ThrowsCount") // Distinct protocol and service rejection boundaries.
    private fun accept(message: SocketMessage) {
        val value = event(message)
        when (value.text("type")) {
            "partial", "final" -> {
                val delta = value.text("delta")
                transcript = value.text("text") ?: (transcript + requireNotNull(delta) { "Missing ASR text" })
                require(transcript.length <= 20000)
                final = value.text("type") == "final"
            }

            "error" -> {
                throw IOException("ASR service rejected input")
            }

            else -> {
                throw IOException("Unexpected ASR event")
            }
        }
    }

    override fun finish(): String {
        check(!closed.get() && !ended)
        ended = true
        socket.end()
        val deadline = nanoTime() + 180_000_000_000L
        while (!final) {
            check(!closed.get())
            if (nanoTime() >= deadline) throw IOException("ASR final timeout")
            socket.receive(1000)?.let { accept(it) }
        }
        check(!closed.get())
        return transcript
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }
}

/** One answer, one duplex connection. Structured cancellation interrupts blocked text input and receive. */
internal class WebSocketTts(
    private val endpoint: String,
    private val connector: SocketConnector,
    private val nanoTime: () -> Long = System::nanoTime,
) : ContinuousTextSpeechEngine {
    private val closed = AtomicBoolean()

    @Volatile private var socket: SocketConnection? = null

    override fun generateStream(
        nextText: () -> String?,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    ) {
        check(!closed.get())
        val stream = connector.connect(endpoint, emptyMap())
        socket = stream
        try {
            if (closed.get()) throw IOException("Speech cancelled")
            stream.ready(24000)
            runBlocking {
                coroutineScope {
                    val inputEnded = AtomicBoolean()
                    val sender =
                        launch(Dispatchers.IO) {
                            var total = 0
                            while (!closed.get() && keepGoing()) {
                                val text = runInterruptible { nextText() } ?: break
                                require(text.isNotBlank() && text.length <= 2000)
                                total += text.length
                                require(total <= 20000)
                                stream.sendText(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    }.toString(),
                                )
                            }
                            check(!closed.get() && keepGoing())
                            inputEnded.set(true)
                            stream.end()
                        }
                    try {
                        receiveAudio(stream, keepGoing, consume)
                        check(inputEnded.get()) { "TTS completed before input ended" }
                        sender.join()
                    } finally {
                        sender.cancel()
                    }
                }
            }
        } finally {
            stream.close()
            socket = null
        }
    }

    private suspend fun receiveAudio(
        stream: SocketConnection,
        keepGoing: () -> Boolean,
        consume: (FloatArray, Int) -> Unit,
    ) {
        val decoder = SpeechPcmDecoder()
        val deadline = nanoTime() + 900_000_000_000L
        var complete = false
        while (!complete) {
            check(!closed.get() && keepGoing())
            if (nanoTime() >= deadline) throw IOException("TTS session timeout")
            val message = runInterruptible(Dispatchers.IO) { stream.receive(1000) } ?: continue
            if (message is SocketMessage.Binary) {
                decoder.accept(message.value) { pcm ->
                    check(!closed.get() && keepGoing())
                    consume(pcm, 24000)
                }
            } else {
                val value = event(message)
                if (value.text("type") != "done") throw IOException("Unexpected TTS event or service error")
                decoder.finish()
                complete = true
            }
        }
    }

    override fun interrupt() = close()

    override fun close() {
        closed.set(true)
        socket?.close()
    }
}

/** Carries an odd byte across WebSocket messages; terminal PCM must contain complete samples. */
private class SpeechPcmDecoder {
    private var carry: Byte? = null
    private var frames = 0L

    fun accept(
        chunk: ByteArray,
        consume: (FloatArray) -> Unit,
    ) {
        val bytes = carry?.let { byteArrayOf(it) + chunk } ?: chunk
        carry = if (bytes.size % 2 == 1) bytes.last() else null
        var offset = 0
        while (offset + 1 < bytes.size) {
            val count = minOf(24000, (bytes.size - offset) / 2)
            val pcm =
                FloatArray(count) { index ->
                    val at = offset + index * 2
                    val value = (bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)
                    value.toShort() / 32768f
                }
            frames += count
            check(frames <= 24000L * 120)
            consume(pcm)
            offset += count * 2
        }
    }

    fun finish() {
        check(carry == null && frames > 0)
    }
}

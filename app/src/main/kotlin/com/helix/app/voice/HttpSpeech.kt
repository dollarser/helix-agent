package com.helix.app.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Keep only this user-started capture in memory; upload once at explicit end-of-input. */
class HttpFileAsr(
    private val session: HttpSpeechSession,
    private val model: String? = null,
    private val responseFormat: String = "json",
) : CloudAsrConnection {
    private val pcm = ByteArrayOutputStream()
    private val closed = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val transcript = StringBuilder()

    init {
        require(responseFormat in setOf("json", "text"))
    }

    override fun send(samples: FloatArray): String {
        check(!closed.get())
        check(!finished.get())
        require(samples.size in 1..16000)
        if (pcm.size() + samples.size * 2 > 16000 * 300 * 2) {
            finished.set(true)
            submitSegment()
            // A failed/ambiguous submission leaves the capture terminal; only success reopens it.
            finished.set(false)
        }
        pcm.write(encodeSpeechPcm(samples))
        return transcript.toString()
    }

    override fun finish(): String {
        check(!closed.get())
        check(finished.compareAndSet(false, true)) { "ASR_ALREADY_SUBMITTED" }
        require(pcm.size() > 0)
        submitSegment()
        return transcript.toString()
    }

    private fun submitSegment() {
        val wav = SpeechWav.encode(pcm.toByteArray(), 16000)
        pcm.reset() // Never replay an ambiguous upload on another finish/send call.
        require(wav.size <= 21 * 1024 * 1024) { "ASR_FILE_LIMIT" }
        val multipart =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .apply {
                    model?.let { addFormDataPart("model", it) }
                    addFormDataPart("response_format", responseFormat)
                    addFormDataPart("file", "capture.wav", wav.toRequestBody("audio/wav".toMediaType()))
                }.build()
        val buffer = Buffer()
        multipart.writeTo(buffer)
        val response = session.post(multipart.contentType().toString(), buffer.readByteArray())
        check(response.size <= 100_000) { "ASR_RESPONSE_LIMIT" }
        val text =
            if (responseFormat == "text") {
                response.toString(Charsets.UTF_8)
            } else {
                val decoded = Json.parseToJsonElement(response.toString(Charsets.UTF_8)).jsonObject
                val value = decoded["text"] as? JsonPrimitive
                require(value != null && value.isString) { "ASR_RESPONSE_INVALID" }
                value.content
            }
        require(text.length <= 20_000) { "ASR_RESPONSE_INVALID" }
        if (transcript.isNotEmpty() && text.isNotEmpty()) transcript.append('\n')
        transcript.append(text)
        require(transcript.length <= 20_000) { "ASR_TEXT_LIMIT" }
    }

    override fun close() {
        closed.set(true)
        session.close()
    }
}

/** One complete HTTP synthesis per submitted text piece; WAV is decoded into bounded PCM callbacks. */
class HttpWavTts(
    private val session: HttpSpeechSession,
) : CloudTtsConnection {
    override fun stream(
        text: String,
        consume: (FloatArray, Int) -> Unit,
    ) {
        require(text.length in 1..2000)
        val body = buildJsonObject { put("input", text) }.toString().toByteArray()
        SpeechWav.consume(session.post("application/json", body), consume)
    }

    override fun close() = session.close()
}

/** HTTP PCM chunks are delivered synchronously, with backpressure, before response EOF. */
class HttpCloudTts(
    private val session: HttpSpeechSession,
    private val model: String = "gpt-realtime-2.1-mini",
    private val voice: String = "marin",
) : CloudTtsConnection {
    override fun stream(
        text: String,
        consume: (FloatArray, Int) -> Unit,
    ) {
        require(text.isNotBlank())
        val streaming = model == "gpt-realtime-2.1-mini"
        for (part in speechCodePointChunks(text)) {
            val body =
                buildJsonObject {
                    put("model", model)
                    put("input", part)
                    put("voice", voice)
                    put("response_format", "pcm")
                    put("speed", 1)
                    if (streaming) put("stream", true)
                }.toString().toByteArray()
            val decoder = SpeechPcmStream(consume)
            if (streaming) {
                session.postStream("application/json", body, decoder::accept)
            } else {
                decoder.accept(session.post("application/json", body))
            }
            decoder.finish()
        }
    }

    override fun close() = session.close()
}

internal fun speechCodePointChunks(text: String): List<String> {
    val result = mutableListOf<String>()
    var offset = 0
    while (offset < text.length) {
        val count = minOf(2000, text.codePointCount(offset, text.length))
        val end = text.offsetByCodePoints(offset, count)
        result.add(text.substring(offset, end))
        offset = end
    }
    return result
}

package com.helix.app.proot

import com.helix.core.policy.DiagnosticRedaction
import com.helix.provider.api.wire.WireBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in owned synthetic streams only. Never records headers, reads ahead, or changes consumer bytes/stop. */
internal class SyntheticSseCapture(
    private val delegate: WireBody,
    private val directory: File,
    private val index: Int,
    private val maxBytes: Int = 2 * 1024 * 1024,
) : WireBody {
    private val bytes = ByteArrayOutputStream()
    private val lengths = mutableListOf<Int>()
    private val elapsed = mutableListOf<Long>()
    private val started = System.nanoTime()
    private val openedAt = System.currentTimeMillis()
    private val closed = AtomicBoolean()
    private var clipped = false
    private var returned = false
    private var consumerStopped = false
    private var failure: String? = null
    private var observationFailure: String? = null
    private var mode = "not-read"

    override suspend fun bytes(): ByteArray {
        mode = "bytes"
        return delegate.bytes().also {
            observe { record(it) }
            returned = true
        }
    }

    // Observe only the class, then rethrow the identical exception.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun forEachChunk(onChunk: suspend (ByteArray) -> Boolean) {
        mode = "chunks"
        try {
            delegate.forEachChunk { chunk ->
                observe { record(chunk) }
                val keepReading = onChunk(chunk)
                if (!keepReading) consumerStopped = true
                keepReading
            }
            returned = true
        } catch (error: Exception) {
            failure = error.javaClass.simpleName
            throw error
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            delegate.close()
        } finally {
            observe { publish() }
        }
    }

    private fun record(chunk: ByteArray) {
        if (clipped) return
        if (bytes.size().toLong() + chunk.size > maxBytes || lengths.size >= 65536) {
            clipped = true
            return
        }
        bytes.write(chunk)
        lengths += chunk.size
        elapsed += (System.nanoTime() - started) / 1_000_000
    }

    // Diagnostics cannot change real streaming; missing/failed evidence never claims complete capture.
    @Suppress("TooGenericExceptionCaught")
    private fun observe(action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            observationFailure = error.javaClass.simpleName
        }
    }

    private fun publish() {
        val original = bytes.toByteArray()
        val text = original.toString(Charsets.UTF_8)
        val validUtf8 = text.toByteArray(Charsets.UTF_8).contentEquals(original)
        val redacted = DiagnosticRedaction.redact(text)
        val unchanged = redacted == text && validUtf8
        val complete = !clipped && observationFailure == null && returned
        if (validUtf8) File(directory, "response-$index.sse.txt").writeText(redacted)
        val replay = syntheticDecoderReplay(original, lengths, complete)
        var offset = 0
        val chunks =
            lengths.mapIndexed { position, length ->
                buildJsonObject {
                    put("index", position)
                    put("offset", offset)
                    put("bytes", length)
                    put("elapsedMs", elapsed[position])
                    put("sha256", sseHash(original.copyOfRange(offset, offset + length)))
                }.also { offset += length }
            }
        File(directory, "response-$index.json").writeText(
            buildJsonObject {
                put("requestIndex", index)
                put("openedAt", openedAt)
                put("closedAt", System.currentTimeMillis())
                put("mode", mode)
                put("bodyBytesCaptured", original.size)
                put("bodySha256", sseHash(original))
                put("completeObservedDelivery", complete)
                put("captureClipped", clipped)
                put("validUtf8", validUtf8)
                put("redactionChanged", !unchanged)
                put("savedTextCanReconstructOriginalChunks", complete && unchanged)
                put("delegateReturned", returned)
                put("consumerStopped", consumerStopped)
                put("delegateReturnedWithoutConsumerStop", returned && !consumerStopped)
                put("networkEof", "not directly observable through WireBody; do not infer from consumer stop")
                failure?.let { put("streamFailureClass", it) }
                observationFailure?.let { put("captureFailureClass", it) }
                put("chunks", JsonArray(chunks))
                put("decoderReplay", replay)
            }.toString(),
        )
        bytes.reset()
    }
}

internal fun sseHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

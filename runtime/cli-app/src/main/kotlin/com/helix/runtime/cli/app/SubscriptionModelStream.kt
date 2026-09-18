package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.provider.api.StreamDecoder
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException

internal fun <T> subscriptionNetwork(block: () -> T): T =
    try {
        block()
    } catch (failure: InterruptedIOException) {
        throw SubscriptionTransportFailure(ModelErrorCode.TIMEOUT, failure)
    } catch (failure: IOException) {
        throw SubscriptionTransportFailure(ModelErrorCode.TRANSPORT, failure)
    }

/** Response handling for subscription protocol adapters; no cumulative reply cap. */
internal fun readSubscriptionEvents(
    response: Response,
    decoder: StreamDecoder,
    onReadFailure: (SubscriptionTransportFailure, Int) -> Unit = { _, _ -> },
    eventDirectory: java.io.File? = null,
    onEvents: (List<ModelEvent>) -> Unit = {},
): List<ModelEvent> {
    if (!response.isSuccessful) {
        return listOf(
            ModelEvent.Error(
                when (response.code) {
                    401, 403 -> ModelErrorCode.AUTH
                    429 -> ModelErrorCode.RATE_LIMITED
                    in 500..599 -> ModelErrorCode.SERVER_ERROR
                    else -> ModelErrorCode.HTTP_ERROR
                },
                response.code == 429 || response.code in 500..599,
            ),
        )
    }
    val spool = eventDirectory?.let(::SpoolingModelEvents)
    var returned = false
    try {
        return readSuccessfulSubscriptionEvents(response, decoder, onEvents, onReadFailure, spool).also {
            returned =
                true
        }
    } finally {
        if (!returned) spool?.close()
    }
}

private fun readSuccessfulSubscriptionEvents(
    response: Response,
    decoder: StreamDecoder,
    onEvents: (List<ModelEvent>) -> Unit,
    onReadFailure: (SubscriptionTransportFailure, Int) -> Unit,
    spool: SpoolingModelEvents?,
): List<ModelEvent> {
    val memory = ArrayList<ModelEvent>()
    val events: List<ModelEvent> = spool ?: memory

    fun append(chunk: List<ModelEvent>) {
        if (spool == null) memory.addAll(chunk) else spool.append(chunk)
    }
    val buffer = Buffer()
    var terminal = false
    while (!terminal) {
        val count =
            try {
                subscriptionNetwork { response.body.source().read(buffer, 16 * 1024L) }
            } catch (failure: SubscriptionTransportFailure) {
                onReadFailure(failure, events.size)
                // Keep the exact preview prefix in the durable result even when the socket fails.
                // Never fabricate completion or replay a partially delivered/tool-bearing request.
                val error = ModelEvent.Error(failure.code, true)
                append(listOf(error))
                onEvents(listOf(error))
                return events
            }
        if (count < 0) break
        val chunk = decoder.feed(buffer.readByteArray())
        append(chunk)
        onEvents(chunk)
        terminal = chunk.any { it is ModelEvent.Completed || it is ModelEvent.Refusal || it is ModelEvent.Error }
    }
    append(decoder.finish())
    return events
}

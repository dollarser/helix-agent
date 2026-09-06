package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.provider.api.StreamDecoder
import okhttp3.Response
import okio.Buffer

/** Common bounded response handling for subscription protocol adapters. */
internal fun readSubscriptionEvents(response: Response, decoder: StreamDecoder): List<ModelEvent> {
    if (!response.isSuccessful) return listOf(ModelEvent.Error(when (response.code) {
        401, 403 -> ModelErrorCode.AUTH
        429 -> ModelErrorCode.RATE_LIMITED
        in 500..599 -> ModelErrorCode.SERVER_ERROR
        else -> ModelErrorCode.HTTP_ERROR
    }, response.code == 429 || response.code in 500..599))
    val events = ArrayList<ModelEvent>()
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val count = response.body.source().read(buffer, 16 * 1024L)
        if (count < 0) break
        total += count
        if (total > CodexSubscriptionModel.MAX_STREAM_BYTES) return listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
        events += decoder.feed(buffer.readByteArray())
        if (events.size > CodexSubscriptionModel.MAX_EVENTS) return listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
    }
    events += decoder.finish()
    return if (events.size <= CodexSubscriptionModel.MAX_EVENTS) events else listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false))
}

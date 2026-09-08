package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.provider.api.StreamDecoder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionModelStreamTest {
    @Test
    fun exactByteLimitFinishesNormally() {
        val decoder = CountingDecoder()
        assertEquals(emptyList<ModelEvent>(), read(CodexSubscriptionModel.MAX_STREAM_BYTES.toInt(), decoder))
        assertEquals(1, decoder.finishes)
    }

    @Test
    fun excessiveBytesFailWithoutFinishingPartialStream() {
        val decoder = CountingDecoder()
        assertProtocolFailure(read(CodexSubscriptionModel.MAX_STREAM_BYTES.toInt() + 1, decoder))
        assertEquals(0, decoder.finishes)
    }

    @Test
    fun excessiveFeedEventsDiscardPartialResultsAndDoNotFinish() {
        val decoder = CountingDecoder(feedEvents = CodexSubscriptionModel.MAX_EVENTS + 1)
        assertProtocolFailure(read(1, decoder))
        assertEquals(0, decoder.finishes)
    }

    @Test
    fun exactEventLimitIsAcceptedButFinishOverflowIsRejected() {
        val exact = CountingDecoder(feedEvents = CodexSubscriptionModel.MAX_EVENTS)
        assertEquals(CodexSubscriptionModel.MAX_EVENTS, read(1, exact).size)
        assertEquals(1, exact.finishes)
        val overflow = CountingDecoder(feedEvents = CodexSubscriptionModel.MAX_EVENTS, finishEvents = 1)
        assertProtocolFailure(read(1, overflow))
        assertEquals(1, overflow.finishes)
    }

    private fun read(
        bytes: Int,
        decoder: StreamDecoder,
    ): List<ModelEvent> =
        Response
            .Builder()
            .request(Request.Builder().url("https://fixture.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(ByteArray(bytes).toResponseBody())
            .build()
            .use { readSubscriptionEvents(it, decoder) }

    private fun assertProtocolFailure(events: List<ModelEvent>) {
        assertEquals(listOf(ModelEvent.Error(ModelErrorCode.PROTOCOL, false)), events)
    }

    private class CountingDecoder(
        private val feedEvents: Int = 0,
        private val finishEvents: Int = 0,
    ) : StreamDecoder {
        var finishes = 0

        override fun feed(chunk: ByteArray): List<ModelEvent> =
            List(feedEvents) { ModelEvent.Error(ModelErrorCode.SERVER_ERROR, false) }

        override fun finish(): List<ModelEvent> {
            finishes++
            return List(finishEvents) { ModelEvent.Error(ModelErrorCode.SERVER_ERROR, false) }
        }
    }
}

package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.provider.api.StreamDecoder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class SubscriptionModelStreamTest {
    @Test fun networkFailuresRetainTransportAndTimeoutClassification() {
        val transport =
            assertThrows(SubscriptionTransportFailure::class.java) {
                subscriptionNetwork { throw IOException("synthetic socket abort") }
            }
        assertEquals(ModelErrorCode.TRANSPORT, transport.code)
        val timeout =
            assertThrows(SubscriptionTransportFailure::class.java) {
                subscriptionNetwork { throw InterruptedIOException("synthetic timeout") }
            }
        assertEquals(ModelErrorCode.TIMEOUT, timeout.code)
    }

    @Test fun invalidProtocolIsNotMisclassifiedAsNetworkFailure() {
        assertThrows(IllegalArgumentException::class.java) {
            subscriptionNetwork { throw IllegalArgumentException("invalid protocol") }
        }
    }

    @Test fun terminalEventDoesNotWaitForSocketEof() {
        var reads = 0
        val source =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (reads++ > 0) throw IOException("socket aborted after protocol completion")
                    sink.writeByte(1)
                    return 1
                }

                override fun timeout() = Timeout.NONE

                override fun close() = Unit
            }.buffer()
        val body =
            object : ResponseBody() {
                override fun contentType() = null

                override fun contentLength() = -1L

                override fun source() = source
            }
        val decoder =
            object : StreamDecoder {
                override fun feed(chunk: ByteArray) = listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop"))

                override fun finish() = emptyList<ModelEvent>()
            }
        Response
            .Builder()
            .request(Request.Builder().url("https://fixture.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
            .use {
                assertEquals(
                    listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed("stop")),
                    readSubscriptionEvents(it, decoder),
                )
            }
        assertEquals(1, reads)
    }

    @Test
    fun formerByteLimitFinishesNormally() {
        val decoder = CountingDecoder()
        assertEquals(emptyList<ModelEvent>(), read(2 * 1024 * 1024, decoder))
        assertEquals(2 * 1024 * 1024, decoder.bytesRead)
        assertEquals(1, decoder.finishes)
    }

    @Test
    fun bytesBeyondTheFormerCapReachTheDecoderAndFinish() {
        val decoder = CountingDecoder()
        assertEquals(emptyList<ModelEvent>(), read(2 * 1024 * 1024 + 1, decoder))
        assertEquals(2 * 1024 * 1024 + 1, decoder.bytesRead)
        assertEquals(1, decoder.finishes)
    }

    @Test
    fun feedEventsBeyondTheFormerCapPreserveOrderAndFinish() {
        val decoder = CountingDecoder(feedEvents = 2049)
        assertEquals(List(2049) { ModelEvent.TextDelta("feed-$it") }, read(1, decoder))
        assertEquals(1, decoder.finishes)
    }

    @Test
    fun finishEventsAppendToTheEntirePreviewBeyondTheFormerCap() {
        val exact = CountingDecoder(feedEvents = 2048)
        assertEquals(List(2048) { ModelEvent.TextDelta("feed-$it") }, read(1, exact))
        assertEquals(1, exact.finishes)
        val overflow = CountingDecoder(feedEvents = 2048, finishEvents = 1)
        assertEquals(
            List(2048) { ModelEvent.TextDelta("feed-$it") } + ModelEvent.TextDelta("finish-0"),
            read(1, overflow),
        )
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

    private class CountingDecoder(
        private val feedEvents: Int = 0,
        private val finishEvents: Int = 0,
    ) : StreamDecoder {
        var finishes = 0
        var bytesRead = 0

        override fun feed(chunk: ByteArray): List<ModelEvent> {
            bytesRead += chunk.size
            return List(feedEvents) { ModelEvent.TextDelta("feed-$it") }
        }

        override fun finish(): List<ModelEvent> {
            finishes++
            return List(finishEvents) { ModelEvent.TextDelta("finish-$it") }
        }
    }
}

package com.helix.runtime.cli.app

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.provider.api.StreamDecoder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.SocketException

class SubscriptionPartialResultTest {
    @Test fun interruptedBodyPersistsExactlyTheDeliveredPrefixAndFailure() {
        var reads = 0
        val source =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (reads++ == 0) {
                        sink.writeUtf8("fragment")
                        return 8L
                    }
                    throw SocketException("synthetic connection abort")
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
                override fun feed(chunk: ByteArray) = listOf(ModelEvent.TextDelta("partial reply"))

                override fun finish(): List<ModelEvent> = error("must not complete an interrupted stream")
            }
        val preview = mutableListOf<ModelEvent>()
        var failureCount = -1
        val result =
            Response
                .Builder()
                .request(Request.Builder().url("https://fixture.invalid/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
                .use {
                    readSubscriptionEvents(it, decoder, onReadFailure = { failure, count ->
                        assertEquals(ModelErrorCode.TRANSPORT, failure.code)
                        failureCount = count
                    }) { chunk -> preview.addAll(chunk) }
                }
        assertEquals(
            listOf(ModelEvent.TextDelta("partial reply"), ModelEvent.Error(ModelErrorCode.TRANSPORT, true)),
            result,
        )
        assertEquals(preview, result)
        assertEquals(1, failureCount)
    }
}

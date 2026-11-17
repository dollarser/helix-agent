package com.helix.provider.openai.chat

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HilexStreamCompletionTest {
    private val finish = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
    private val usage = "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1}}\n\n"
    private val done = "data: [DONE]\n\n"

    @Test fun cloudCompletionWaitsForUsageAndDone() {
        val decoder = ChatCompletionsStreamDecoder(requireUsageAndDone = true)
        assertFalse(decoder.feed(finish.toByteArray()).any { it is ModelEvent.Completed })
        assertFalse(decoder.feed(usage.toByteArray()).any { it is ModelEvent.Completed })
        assertTrue(decoder.feed(done.toByteArray()).any { it is ModelEvent.Completed })
        assertTrue(decoder.finish().isEmpty())
    }

    @Test fun missingDoneOrUsageIsIncomplete() {
        listOf(finish + usage, finish + done).forEach { wire ->
            val decoder = ChatCompletionsStreamDecoder(requireUsageAndDone = true)
            assertFalse(decoder.feed(wire.toByteArray()).any { it is ModelEvent.Completed })
            assertTrue(decoder.finish().any { it is ModelEvent.Error })
        }
    }

    @Test fun finalDoneWithoutBlankSeparatorCompletesAtEof() {
        val decoder = ChatCompletionsStreamDecoder(requireUsageAndDone = true)
        assertFalse(decoder.feed((finish + usage + "data: [DONE]\n").toByteArray()).any { it is ModelEvent.Completed })
        val events = decoder.finish()
        assertTrue(events.any { it is ModelEvent.Completed })
        assertFalse(events.any { it is ModelEvent.Error })
    }

    @Test fun upstreamErrorAfterFinishReasonCannotBecomeSuccess() {
        val decoder = ChatCompletionsStreamDecoder(requireUsageAndDone = true)
        val error = "data: {\"error\":{\"code\":\"provider_stream_interrupted\"}}\n\n"
        val events = decoder.feed((finish + usage + error + done).toByteArray()) + decoder.finish()
        assertTrue(events.any { it is ModelEvent.Error })
        assertFalse(events.any { it is ModelEvent.Completed })
    }

    @Test fun genericCompatibleEndpointKeepsExistingContract() {
        assertTrue(ChatCompletionsStreamDecoder().feed(finish.toByteArray()).any { it is ModelEvent.Completed })
    }
}

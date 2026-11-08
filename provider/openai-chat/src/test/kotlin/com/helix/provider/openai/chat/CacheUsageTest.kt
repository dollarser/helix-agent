package com.helix.provider.openai.chat

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheUsageTest {
    @Test fun serverReportedCacheIsASubsetNotAnAdditionalInputCharge() {
        assertEquals(
            ModelEvent.Usage(100, 3, 80),
            decode("""{"prompt_tokens":100,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":80}}"""),
        )
        assertEquals(
            ModelEvent.Usage(100, 3, 0),
            decode("""{"prompt_tokens":100,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":0}}"""),
        )
        assertEquals(ModelEvent.Usage(100, 3), decode("""{"prompt_tokens":100,"completion_tokens":3}"""))
    }

    @Test fun invalidOrMissingCacheCountsRemainUnknown() {
        for (invalid in listOf("-1", "\"4\"", "null", "{}", "[]", "1.5", "true", "9223372036854775808")) {
            assertEquals(
                ModelEvent.Usage(100, 3),
                decode(
                    """{"prompt_tokens":100,"completion_tokens":3,""" +
                        """"prompt_tokens_details":{"cached_tokens":$invalid}}""",
                ),
            )
        }
        for (details in listOf("null", "{}", "[]", "42")) {
            assertEquals(
                ModelEvent.Usage(100, 3),
                decode("""{"prompt_tokens":100,"completion_tokens":3,"prompt_tokens_details":$details}"""),
            )
        }
    }

    @Test fun malformedTotalsRemainUnknownWithoutFailingValidCacheTelemetry() {
        for (invalid in listOf("-1", "\"4\"", "null", "{}", "1.5", "true")) {
            assertEquals(
                ModelEvent.Usage(null, null),
                decode("""{"prompt_tokens":$invalid,"completion_tokens":$invalid}"""),
            )
        }
    }

    private fun decode(usage: String): ModelEvent.Usage {
        val wire =
            "data: {\"choices\":[],\"usage\":$usage}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        val decoder = ChatCompletionsStreamDecoder()
        return (wire.toByteArray().toList().flatMap { decoder.feed(byteArrayOf(it)) } + decoder.finish())
            .filterIsInstance<ModelEvent.Usage>()
            .single()
    }
}

package com.helix.provider.openai.responses

import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class CacheUsageTest {
    @Test fun cacheWritesUseAggregateWithoutAddingTtlBreakdown() {
        assertEquals(
            ModelEvent.Usage(100, 3, 80, 10),
            decode(
                """{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cached_tokens":80,
            "cache_write_tokens":10,"cache_write_tokens_details":{"ttl_30m_tokens":4,"ttl_12h_tokens":6}}}""".replace(
                    "\n",
                    "",
                ),
            ),
        )
        assertEquals(
            ModelEvent.Usage(100, 3, null, 0),
            decode(
                """{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cache_write_tokens":0}}""",
            ),
        )
        for (invalid in listOf("-1", "\"4\"", "null", "{}", "[]", "1.5", "true")) {
            assertEquals(
                ModelEvent.Usage(100, 3),
                decode(
                    """{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cache_write_tokens":$invalid}}""",
                ),
            )
        }
    }

    @Test fun serverReportedCacheIsASubsetNotAnAdditionalInputCharge() {
        assertEquals(
            ModelEvent.Usage(100, 3, 80),
            decode("""{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cached_tokens":80}}"""),
        )
        assertEquals(
            ModelEvent.Usage(100, 3, 0),
            decode("""{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cached_tokens":0}}"""),
        )
        assertEquals(ModelEvent.Usage(100, 3), decode("""{"input_tokens":100,"output_tokens":3}"""))
    }

    @Test fun invalidOrMissingCacheCountsRemainUnknown() {
        for (invalid in listOf("-1", "\"4\"", "null", "{}", "[]", "1.5", "true", "9223372036854775808")) {
            assertEquals(
                ModelEvent.Usage(100, 3),
                decode("""{"input_tokens":100,"output_tokens":3,"input_tokens_details":{"cached_tokens":$invalid}}"""),
            )
        }
        for (details in listOf("null", "{}", "[]", "42")) {
            assertEquals(
                ModelEvent.Usage(100, 3),
                decode("""{"input_tokens":100,"output_tokens":3,"input_tokens_details":$details}"""),
            )
        }
    }

    @Test fun malformedTotalsRemainUnknownWithoutFailingValidCacheTelemetry() {
        for (invalid in listOf("-1", "\"4\"", "null", "{}", "1.5", "true")) {
            assertEquals(ModelEvent.Usage(null, null), decode("""{"input_tokens":$invalid,"output_tokens":$invalid}"""))
        }
    }

    private fun decode(usage: String): ModelEvent.Usage {
        val wire =
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":0," +
                "\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"usage\":$usage}}\n\n"
        val decoder = ResponsesStreamDecoder()
        return (wire.toByteArray().toList().flatMap { decoder.feed(byteArrayOf(it)) } + decoder.finish())
            .filterIsInstance<ModelEvent.Usage>()
            .single()
    }
}

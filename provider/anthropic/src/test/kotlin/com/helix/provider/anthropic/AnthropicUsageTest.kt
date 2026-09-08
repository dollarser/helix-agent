package com.helix.provider.anthropic

import com.helix.core.model.ModelEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnthropicUsageTest {
    @Test fun cachedAndNewInputAllCountOnce() {
        assertEquals(
            104L,
            input("""{"input_tokens":12,"cache_creation_input_tokens":4,"cache_read_input_tokens":88}"""),
        )
        assertEquals(88L, input("""{"input_tokens":0,"cache_read_input_tokens":88}"""))
        assertEquals(12L, input("""{"input_tokens":12}"""))
    }

    @Test fun cacheBreakdownDoesNotDoubleCountAggregate() {
        assertEquals(
            104L,
            input(
                """{"input_tokens":12,"cache_creation_input_tokens":4,"cache_read_input_tokens":88,
            "cache_creation":{"ephemeral_5m_input_tokens":3,"ephemeral_1h_input_tokens":1}}""",
            ),
        )
    }

    @Test fun missingOrInvalidPartsKeepUsageUnknownForFallback() {
        listOf(
            "{}",
            """{"cache_read_input_tokens":8}""",
            """{"input_tokens":-1}""",
            """{"input_tokens":12,"cache_read_input_tokens":-1}""",
            """{"input_tokens":12,"cache_creation_input_tokens":"4"}""",
        ).forEach { assertNull(input(it)) }
    }

    @Test fun overflowSaturatesInsteadOfWrappingBudget() {
        assertEquals(Long.MAX_VALUE, input("""{"input_tokens":9223372036854775800,"cache_read_input_tokens":88}"""))
    }

    @Test fun streamEmitsFullCachedInputAtTerminal() {
        val start =
            """{"type":"message_start","message":{"usage":{"input_tokens":12,
            "cache_creation_input_tokens":4,"cache_read_input_tokens":88}}}""".replace("\n", "")
        val delta = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":3}}"""
        val wire =
            "event: message_start\ndata: $start\n\nevent: message_delta\ndata: $delta\n\n" +
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        val decoder = AnthropicStreamDecoder()
        val events = decoder.feed(wire.toByteArray()) + decoder.finish()
        assertEquals(listOf(ModelEvent.Usage(104, 3), ModelEvent.Completed("stop")), events)
    }

    private fun input(json: String) = AnthropicUsage.inputTokens(Json.parseToJsonElement(json).jsonObject)
}

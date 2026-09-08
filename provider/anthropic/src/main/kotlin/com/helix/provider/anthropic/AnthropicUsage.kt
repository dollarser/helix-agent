package com.helix.provider.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Goal/Turn token budgets count all input tokens, independently of cache pricing discounts. */
internal object AnthropicUsage {
    fun inputTokens(usage: JsonObject): Long? {
        val input = count(usage, "input_tokens")
        val created = optionalCount(usage, "cache_creation_input_tokens")
        val cached = optionalCount(usage, "cache_read_input_tokens")
        return if (input == null || created == null || cached == null) null else add(add(input, created), cached)
    }

    private fun optionalCount(
        usage: JsonObject,
        name: String,
    ): Long? = if (name in usage) count(usage, name) else 0L

    private fun count(
        usage: JsonObject,
        name: String,
    ): Long? {
        val value = usage[name] as? JsonPrimitive ?: return null
        return value.longOrNull?.takeIf { !value.isString && it >= 0 }
    }

    private fun add(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}

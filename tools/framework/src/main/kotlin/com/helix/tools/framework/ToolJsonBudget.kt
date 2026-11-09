package com.helix.tools.framework

import com.helix.core.model.JsonInputLimits
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Iterative preflight bounds recursion and work even for values without a constraining schema. */
object ToolJsonBudget {
    const val MAX_DEPTH = JsonInputLimits.MAX_DEPTH
    const val MAX_NODES = JsonInputLimits.MAX_NODES

    @Suppress("ReturnCount") // Stop immediately at each resource boundary.
    fun violation(
        root: JsonElement,
        checkNumbers: Boolean = true,
    ): String? {
        val pending = ArrayDeque<Pair<JsonElement, Int>>()
        pending.addLast(root to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            if (++nodes > MAX_NODES) return "JSON exceeds $MAX_NODES nodes"
            if (depth > MAX_DEPTH) return "JSON exceeds nesting depth $MAX_DEPTH"
            when (value) {
                is JsonObject -> {
                    if (value.size > MAX_NODES - nodes - pending.size) return "JSON exceeds $MAX_NODES nodes"
                    value.values.forEach { pending.addLast(it to depth + 1) }
                }

                is JsonArray -> {
                    if (value.size > MAX_NODES - nodes - pending.size) return "JSON exceeds $MAX_NODES nodes"
                    value.forEach { pending.addLast(it to depth + 1) }
                }

                is JsonPrimitive -> {
                    if (checkNumbers && invalidNumber(value)) {
                        return "invalid JSON number or numeric resource limit exceeded"
                    }
                }
            }
        }
        return null
    }

    private fun invalidNumber(value: JsonPrimitive): Boolean {
        if (value == JsonNull || value.isString || ToolJsonPrimitives.isBoolean(value)) return false
        return ToolJsonNumbers.decimalOrNull(value) == null
    }
}

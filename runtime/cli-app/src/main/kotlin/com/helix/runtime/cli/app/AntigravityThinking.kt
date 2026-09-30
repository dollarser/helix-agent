package com.helix.runtime.cli.app

import com.helix.core.model.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wire-family mapping for an explicitly requested effort, not discovery or evidence of model capability. */
internal fun antigravityThinking(
    model: String,
    effort: ReasoningEffort,
    outputLimit: Long?,
): JsonObject {
    val level = effort.name.lowercase(java.util.Locale.ROOT)
    require(level in setOf("low", "medium", "high"))
    val budget = thinkingBudget(model, level)
    require(outputLimit == null || budget <= 0 || outputLimit > budget) { "Output budget cannot fit thinking" }
    return buildJsonObject {
        put("includeThoughts", true)
        put("thinkingBudget", budget)
    }
}

private fun thinkingBudget(
    model: String,
    level: String,
): Int =
    when {
        model.startsWith("claude-") -> {
            require(level == "high")
            1024
        }

        model.startsWith("gpt-oss-") -> {
            require(level == "medium")
            8192
        }

        model.startsWith("gemini-3.1-pro") || model == "gemini-pro-agent" -> {
            require(level != "medium")
            if (level == "high") 10001 else 1001
        }

        model.startsWith("gemini-3.5-flash") || model == "gemini-3-flash-agent" -> {
            mapOf("high" to 10000, "medium" to 4000, "low" to 1000).getValue(level)
        }

        Regex("^gemini-(2\\.5|3).*").matches(model) -> {
            mapOf("high" to -1, "medium" to 4000, "low" to 1000).getValue(level)
        }

        else -> {
            throw IllegalArgumentException("Unknown Antigravity reasoning dialect")
        }
    }

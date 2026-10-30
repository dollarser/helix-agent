package com.helix.core.agent

import com.helix.core.model.ModelEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Client-side elapsed observations, not GPU timings, billing or a second execution ledger. */
data class ModelClientObservation(
    val durationMs: Long?,
    val firstResponseMs: Long?,
    val firstTextMs: Long?,
    val summary: Boolean,
) {
    init {
        require(listOfNotNull(durationMs, firstResponseMs, firstTextMs).all { it >= 0 })
        require(durationMs == null || listOfNotNull(firstResponseMs, firstTextMs).all { it <= durationMs })
        require(firstResponseMs == null || firstTextMs == null || firstResponseMs <= firstTextMs)
    }

    fun json(): JsonObject =
        buildJsonObject {
            durationMs?.let { put("durationMs", it) }
            firstResponseMs?.let { put("firstResponseMs", it) }
            firstTextMs?.let { put("firstTextMs", it) }
            put("summary", summary)
        }

    companion object {
        fun parse(value: JsonObject): ModelClientObservation {
            require(value.keys.all { it in setOf("durationMs", "firstResponseMs", "firstTextMs", "summary") })

            fun number(key: String): Long? =
                value[key]?.jsonPrimitive?.let {
                    require(!it.isString)
                    requireNotNull(it.longOrNull)
                }
            require(!value.getValue("summary").jsonPrimitive.isString)
            return ModelClientObservation(
                number("durationMs"),
                number("firstResponseMs"),
                number("firstTextMs"),
                value.getValue("summary").jsonPrimitive.boolean,
            )
        }
    }
}

internal class ModelRequestObservation(
    private val nanos: () -> Long,
) {
    private val started = nanos()
    private var firstResponse: Long? = null
    private var firstText: Long? = null

    private fun elapsed(): Long? = (nanos() - started).takeIf { it >= 0 }?.div(1_000_000)

    fun event(
        event: ModelEvent,
        acceptedText: Boolean,
    ) {
        val text = event is ModelEvent.TextDelta && acceptedText && firstText == null
        val response =
            firstResponse == null &&
                (acceptedText || event is ModelEvent.ReasoningDelta || event is ModelEvent.ToolCallStarted)
        if (text || response) {
            val time = elapsed()
            if (text) firstText = time
            if (response) firstResponse = time
        }
    }

    fun finish(summary: Boolean): ModelClientObservation =
        ModelClientObservation(elapsed(), firstResponse, firstText, summary)
}

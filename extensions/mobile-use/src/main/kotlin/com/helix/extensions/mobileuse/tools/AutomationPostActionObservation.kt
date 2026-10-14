package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.concurrent.CancellationException

/** An optional read after a known dispatch never changes the action's effect or replays it. */
internal class AutomationPostActionObservation(
    private val capture: () -> AutomationSnapshotResult,
    private val encode: (AutomationSnapshotResult) -> JsonObject,
    private val now: () -> Instant = Instant::now,
) {
    fun attach(
        result: ToolExecutorResult,
        call: ExecutableToolCall,
    ): ToolExecutorResult {
        if (result !is ToolExecutorResult.Completed ||
            result.output.jsonObject["status"]
                ?.jsonPrimitive
                ?.content != "DISPATCHED"
        ) {
            return result
        }
        val read = observe(call)
        val status = read.status
        val fields = result.output.jsonObject.toMutableMap()
        fields["observationStatus"] = JsonPrimitive(status)
        read.observation?.let { fields["observation"] = it }
        if (status == "ATTACHED") {
            fields["nextObservation"] =
                JsonPrimitive(
                    "Read observation below; use its fresh tokens. Query again only if needed.",
                )
            fields["actionHint"] =
                JsonPrimitive(
                    "Input dispatched; effect remains UNVERIFIED. Earlier tokens/frames are invalid. " +
                        "Read the attached observation and use only its fresh tokens for the next action. " +
                        "Check truncated before assuming a target is absent. This state may still be loading; " +
                        "verify the requested effect, and observe again if needed. " +
                        "Never blindly replay an uncertain action.",
                )
        }
        return result.copy(output = JsonObject(fields))
    }

    private fun observe(call: ExecutableToolCall): ObservationRead {
        admission(call)?.let { return ObservationRead(it) }
        return try {
            val fresh = capture()
            val stopped = admission(call)
            if (stopped != null) {
                ObservationRead(stopped)
            } else {
                when {
                    fresh.status != AutomationSnapshotStatus.SUCCESS -> {
                        ObservationRead("UNAVAILABLE", encode(fresh.copy(snapshot = null)))
                    }

                    fresh.snapshot == null -> {
                        ObservationRead("UNAVAILABLE")
                    }

                    else -> {
                        ObservationRead("ATTACHED", bounded(encode(fresh)))
                    }
                }
            }
        } catch (_: CancellationException) {
            // Only the optional read was cancelled, after a known input acknowledgement.
            ObservationRead("SKIPPED_CANCELLED")
        } catch (_: RuntimeException) {
            // The input acknowledgement remains true; there is no successful observation.
            ObservationRead("UNAVAILABLE")
        }
    }

    private data class ObservationRead(
        val status: String,
        val observation: JsonObject? = null,
    )

    private fun admission(call: ExecutableToolCall): String? =
        when {
            call.cancel.isCancelled() -> "SKIPPED_CANCELLED"
            !now().isBefore(call.deadline) -> "SKIPPED_DEADLINE"
            else -> null
        }

    private fun bounded(observation: JsonObject): JsonObject {
        val nodes = observation["nodes"]?.jsonArray ?: return observation
        var bytes = 0
        val selected =
            nodes.take(MAX_NODES).takeWhile {
                bytes += it.toString().toByteArray(Charsets.UTF_8).size + 1
                bytes <= MAX_NODE_BYTES
            }
        return if (selected.size == nodes.size) {
            observation
        } else {
            JsonObject(
                observation +
                    mapOf(
                        "nodes" to JsonArray(selected),
                        "truncated" to JsonPrimitive(true),
                        "recoveryHint" to
                            JsonPrimitive(
                                "Post-action observation shortened. Use ui.find for a needed target; " +
                                    "this replaces these tokens. " +
                                    observation["recoveryHint"]
                                        ?.jsonPrimitive
                                        ?.content
                                        .orEmpty()
                                        .take(350),
                            ),
                    ),
            )
        }
    }

    companion object {
        val STATUSES = listOf("ATTACHED", "UNAVAILABLE", "SKIPPED_CANCELLED", "SKIPPED_DEADLINE")
        private const val MAX_NODES = 32
        private const val MAX_NODE_BYTES = 12 * 1024
    }
}

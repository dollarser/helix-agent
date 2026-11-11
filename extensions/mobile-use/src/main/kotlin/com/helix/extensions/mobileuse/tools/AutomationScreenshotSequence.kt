package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.AutomationTermination
import com.helix.extensions.mobileuse.automation.AutomationDevicePort
import com.helix.extensions.mobileuse.automation.AutomationScreenshot
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/** One original call, serial captures, no input or frame refresh across a changed target. */
internal class AutomationScreenshotSequence(
    private val port: AutomationDevicePort,
    private val now: () -> Instant = Instant::now,
    private val nanos: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val sheet: () -> ScreenshotSheet = { AutomationContactSheet() },
) {
    data class Result(
        val capture: AutomationScreenshot,
        val samples: JsonArray,
        val termination: ToolExecutorResult? = null,
    )

    // Keep unpublished pixel ownership and all abort paths in one try/finally.
    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    fun capture(
        original: ExecutableToolCall,
        frame: String,
        count: Int,
        interval: Long,
    ): Result {
        require(count in 1..4 && interval in 500..3000)
        val call =
            if (count ==
                1
            ) {
                original
            } else {
                original.copy(deadline = minOf(original.deadline, now().plusSeconds(15)))
            }
        val bound = port.forCall(call)
        val samples = mutableListOf<JsonObject>()
        val start = nanos()
        var token = frame
        var first: AutomationScreenshot? = null
        val contact = if (count > 1) sheet() else null
        try {
            repeat(count) { index ->
                if (index > 0) waitInterval(call, interval)
                AutomationTermination.beforeDispatch(call)?.let {
                    return Result(AutomationScreenshot("CAPTURE_ABORTED"), JsonArray(samples), it)
                }
                val started = now()
                val tick = nanos()
                val captured = bound.screenshot(token, call)
                val finished = now()
                val elapsed = (nanos() - tick) / NANOS_PER_MILLI
                if (captured.png == null) return Result(captured, JsonArray(samples))
                AutomationTermination.beforeDispatch(call)?.let {
                    return Result(AutomationScreenshot("CAPTURE_ABORTED"), JsonArray(samples), it)
                }
                val initial = first
                if (initial != null && !sameMapping(initial, captured)) {
                    return Result(AutomationScreenshot("CAPTURE_MAPPING_CHANGED"), JsonArray(samples))
                }
                // Keep metadata only after the tile is drawn; do not retain every full-size PNG.
                if (index == 0) first = if (contact == null) captured else captured.copy(png = null)
                contact?.add(captured, index, count)
                samples +=
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("requestStartedAt", JsonPrimitive(started.toString()))
                        put("requestFinishedAt", JsonPrimitive(finished.toString()))
                        put("offsetMillis", JsonPrimitive((tick - start) / NANOS_PER_MILLI))
                        put("durationMillis", JsonPrimitive(elapsed))
                    }
                token = captured.frame ?: token
            }
            AutomationTermination.beforeDispatch(call)?.let {
                return Result(AutomationScreenshot("CAPTURE_ABORTED"), JsonArray(samples), it)
            }
            val captured = checkNotNull(first)
            val image = contact?.finish(captured.acquisitionScopeRef) ?: captured
            AutomationTermination.beforeDispatch(call)?.let {
                return Result(AutomationScreenshot("CAPTURE_ABORTED"), JsonArray(samples), it)
            }
            return Result(image, JsonArray(samples))
        } finally {
            contact?.close()
        }
    }

    private fun sameMapping(
        first: AutomationScreenshot,
        next: AutomationScreenshot,
    ): Boolean =
        first.acquisitionScopeRef == next.acquisitionScopeRef && first.screenBounds == next.screenBounds &&
            first.width == next.width && first.height == next.height

    private fun waitInterval(
        call: ExecutableToolCall,
        interval: Long,
    ) {
        val end = nanos() + interval * NANOS_PER_MILLI
        while (nanos() < end && AutomationTermination.beforeDispatch(call) == null) {
            sleep(minOf(50, ((end - nanos()) / NANOS_PER_MILLI).coerceAtLeast(1)))
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

internal interface ScreenshotSheet : AutoCloseable {
    fun add(
        capture: AutomationScreenshot,
        index: Int,
        count: Int,
    )

    fun finish(scope: String?): AutomationScreenshot
}

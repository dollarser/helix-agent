package com.helix.extensions.mobileuse.tools

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationAppListing
import com.helix.extensions.mobileuse.automation.AutomationDeviceObservation
import com.helix.extensions.mobileuse.automation.AutomationDevicePort
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationScreenshot
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationScreenshotSequenceTest {
    private var tick = 0L
    private var cancelled = false
    private val frames = mutableListOf<String>()
    private val calls = mutableListOf<ExecutableToolCall>()
    private var refuseAt = -1
    private var changeScopeAt = -1
    private var changeBoundsAt = -1
    private var cancelAfter = -1
    private var closed = false
    private var finished = false
    private val tiles = mutableListOf<Int>()
    private val start = Instant.now()
    private val call =
        ExecutableToolCall(
            "call",
            "ui.screenshot",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            start.plusSeconds(60),
            object : CancelSignal {
                override fun isCancelled() = cancelled
            },
            "session",
            "turn",
        )
    private val port =
        object : AutomationDevicePort {
            override fun observe(): AutomationDeviceObservation = error("No automatic observation")

            override fun apps(packageName: String?): AutomationAppListing = error("No apps")

            override fun launch(
                packageName: String,
                call: ExecutableToolCall,
            ): AutomationActionResult = error("No launch")

            override fun gesture(
                frame: String,
                strokes: List<AutomationStroke>,
                call: ExecutableToolCall,
            ): AutomationActionResult = error("No gesture")

            override fun screenshot(
                frame: String,
                call: ExecutableToolCall,
            ): AutomationScreenshot {
                frames += frame
                calls += call
                tick += 120_000_000
                val index = frames.lastIndex
                if (index == cancelAfter) cancelled = true
                if (index == refuseAt) return AutomationScreenshot("TARGET_CHANGED")
                return AutomationScreenshot(
                    "SAVED",
                    byteArrayOf(index.toByte()),
                    10,
                    20,
                    AutomationNodeBounds(0, 0, if (index == changeBoundsAt) 11 else 10, 20),
                    if (index == changeScopeAt) "other-grant" else "grant",
                    "frame-${index + 1}",
                )
            }
        }

    private fun sequence(onWait: () -> Unit = {}) =
        AutomationScreenshotSequence(
            port,
            now = { start.plusNanos(tick) },
            nanos = { tick },
            sleep = {
                tick += it * 1_000_000
                onWait()
            },
            sheet = {
                object : ScreenshotSheet {
                    override fun add(
                        capture: AutomationScreenshot,
                        index: Int,
                        count: Int,
                    ) {
                        tiles += index
                    }

                    override fun finish(scope: String?): AutomationScreenshot {
                        finished = true
                        return AutomationScreenshot("SAVED", byteArrayOf(9), 20, 40, acquisitionScopeRef = scope)
                    }

                    override fun close() {
                        closed = true
                    }
                }
            },
        )

    @Test fun deviceCapturesKeepOriginalIdentityAndUseReturnedFrameWithoutRoundTrips() {
        val result = sequence().capture(call, "initial", 4, 1000)
        assertEquals(listOf("initial", "frame-1", "frame-2", "frame-3"), frames)
        assertEquals(listOf(0, 1, 2, 3), tiles)
        assertTrue(
            calls.all {
                it.toolCallId == call.toolCallId && it.sessionId == call.sessionId &&
                    it.turnId == call.turnId
            },
        )
        assertTrue(calls.all { it.deadline == start.plusSeconds(15) })
        assertEquals(
            listOf("0", "1120", "2240", "3360"),
            result.samples.map {
                it.jsonObject
                    .getValue("offsetMillis")
                    .jsonPrimitive.content
            },
        )
        assertTrue(
            result.samples.all {
                it.jsonObject
                    .getValue("durationMillis")
                    .jsonPrimitive.content == "120"
            },
        )
        assertNull(result.capture.frame)
        assertNull(result.capture.screenBounds)
        assertTrue(finished && closed)
    }

    @Test fun cancellationDuringSpacingStopsBeforeNextCaptureAndDiscardsPixels() {
        val result = sequence { cancelled = true }.capture(call, "initial", 4, 1000)
        assertEquals(1, frames.size)
        assertTrue(result.termination is ToolExecutorResult.CancelledWithEffectTruth)
        assertNull(result.capture.png)
        assertTrue(closed)
        assertFalse(finished)
    }

    @Test fun cancellationAfterCaptureDiscardsCurrentAndEarlierFrames() {
        cancelAfter = 1
        val result = sequence().capture(call, "initial", 4, 500)
        assertTrue(result.termination is ToolExecutorResult.CancelledWithEffectTruth)
        assertEquals(2, frames.size)
        assertEquals(listOf(0), tiles)
        assertNull(result.capture.png)
        assertTrue(closed)
    }

    @Test fun changedTargetDoesNotRefreshOrPublishAPartialSequence() {
        refuseAt = 1
        val result = sequence().capture(call, "initial", 4, 500)
        assertEquals("TARGET_CHANGED", result.capture.status)
        assertNull(result.capture.png)
        assertEquals(2, frames.size)
        assertFalse(finished)
        assertTrue(closed)
    }

    @Test fun changedGrantOrMappingNeverCombinesImages() {
        changeScopeAt = 1
        assertEquals("CAPTURE_MAPPING_CHANGED", sequence().capture(call, "initial", 4, 500).capture.status)
        frames.clear()
        changeScopeAt = -1
        changeBoundsAt = 1
        assertEquals("CAPTURE_MAPPING_CHANGED", sequence().capture(call, "initial", 4, 500).capture.status)
        assertFalse(finished)
    }

    @Test fun expiredOriginalCallDoesNotCapture() {
        val result = sequence().capture(call.copy(deadline = Instant.EPOCH), "initial", 4, 500)
        assertTrue(result.termination is ToolExecutorResult.TimedOutWithEffectTruth)
        assertTrue(frames.isEmpty())
    }

    @Test fun singleCaptureKeepsNativeMappingAndReportsRealRequestInterval() {
        val result = sequence().capture(call, "initial", 1, 1000)
        assertEquals("frame-1", result.capture.frame)
        assertNotNull(result.capture.screenBounds)
        assertEquals(
            start.toString(),
            result.samples
                .single()
                .jsonObject
                .getValue("requestStartedAt")
                .jsonPrimitive.content,
        )
        assertEquals(
            start.plusMillis(120).toString(),
            result.samples
                .single()
                .jsonObject
                .getValue("requestFinishedAt")
                .jsonPrimitive.content,
        )
        assertFalse(closed)
    }

    @Test fun invalidBoundsNeverReachTheDevice() {
        for ((count, interval) in listOf(0 to 1000L, 5 to 1000L, 4 to 499L, 4 to 3001L)) {
            assertThrows(IllegalArgumentException::class.java) { sequence().capture(call, "initial", count, interval) }
        }
        assertTrue(frames.isEmpty())
    }
}

package com.helix.extensions.mobileuse.tools

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AutomationSettledObservationTest {
    private var instant = Instant.parse("2026-10-14T00:00:00Z")
    private var cancelled = false
    private var reads = 0
    private var capture: () -> AutomationSnapshotResult = { snapshot() }
    private var sleep: (Duration) -> Unit = { instant = instant.plus(it) }

    private fun snapshot(
        text: String = "Hello",
        top: Int = 0,
        truncated: Boolean = false,
    ) = AutomationSnapshotResult(
        AutomationSnapshotStatus.SUCCESS,
        AutomationSnapshot(
            "fixture",
            1,
            reads.toLong(),
            instant,
            listOf(
                AutomationSnapshotNode(
                    "token-$reads",
                    null,
                    0,
                    "ScrollView",
                    text,
                    null,
                    "form",
                    AutomationNodeBounds(0, top, 100, 100),
                    false,
                    false,
                    false,
                    true,
                    true,
                ),
            ),
            truncated,
        ),
    )

    private fun attach(remainingMillis: Long = 30_000): JsonObject {
        val call =
            ExecutableToolCall(
                "original-call",
                "ui.scroll",
                "6",
                JsonObject(emptyMap()),
                ExecutionTargetType.LOCAL_ANDROID,
                instant.plusMillis(remainingMillis),
                object : CancelSignal {
                    override fun isCancelled() = cancelled
                },
            )
        val helper =
            AutomationPostActionObservation(
                capture = {
                    reads++
                    capture()
                },
                encode = { result ->
                    JsonObject(
                        buildMap {
                            put("status", JsonPrimitive(result.status.name))
                            result.snapshot?.nodes?.single()?.let {
                                put("text", JsonPrimitive(it.text))
                                put("top", JsonPrimitive(it.bounds.top))
                                put("token", JsonPrimitive(it.token))
                            }
                        },
                    )
                },
                now = { instant },
                sleep = { sleep(it) },
            )
        val output =
            (
                helper.attach(
                    AutomationActionResult(AutomationActionStatus.SUCCEEDED).toToolOutcome(),
                    call,
                ) as ToolExecutorResult.Completed
            ).output.jsonObject
        assertEquals("DISPATCHED", output.getValue("status").jsonPrimitive.content)
        assertEquals("UNVERIFIED", output.getValue("effectStatus").jsonPrimitive.content)
        return output
    }

    @Test fun delayedTextUpdateMustSettleBeforePublishingItsLatestToken() {
        capture = { snapshot(if (reads == 1) "old" else "Hello") }
        val output = attach()
        assertEquals("ATTACHED", output.getValue("observationStatus").jsonPrimitive.content)
        assertEquals(
            "Hello",
            output
                .getValue("observation")
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content,
        )
        assertEquals(6, reads)
        assertEquals(
            "token-6",
            output
                .getValue("observation")
                .jsonObject
                .getValue("token")
                .jsonPrimitive.content,
        )
    }

    @Test fun movingScrollIsReadUntilItsGeometrySettles() {
        capture = { snapshot(top = minOf(reads, 5) * 10) }
        val output = attach()
        assertEquals("ATTACHED", output.getValue("observationStatus").jsonPrimitive.content)
        assertEquals(
            "50",
            output
                .getValue("observation")
                .jsonObject
                .getValue("top")
                .jsonPrimitive.content,
        )
        assertEquals(9, reads)
    }

    @Test fun continuousMotionHasOneSecondBoundAndNeverPublishesTransientTokens() {
        capture = { snapshot(top = reads) }
        val start = instant
        val output = attach()
        assertEquals("UNSETTLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(Duration.ofSeconds(1), Duration.between(start, instant))
        assertEquals(20, reads)
    }

    @Test fun originalDeadlineWinsOverTheOptionalStabilityWindow() {
        val output = attach(remainingMillis = 150)
        assertEquals("SKIPPED_DEADLINE", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(3, reads)
    }

    @Test fun cancellationDuringTheQuietPeriodCannotPublishMoreTokens() {
        sleep = {
            cancelled = true
            instant = instant.plus(it)
        }
        val output = attach()
        assertEquals("SKIPPED_CANCELLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(1, reads)
    }

    @Test fun revokedScopeStopsWithoutPublishingEarlierAuthorizedNodes() {
        capture = {
            if (reads == 1) snapshot() else AutomationSnapshotResult(AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED)
        }
        val output = attach()
        assertEquals("UNAVAILABLE", output.getValue("observationStatus").jsonPrimitive.content)
        val observation = output.getValue("observation").jsonObject
        assertEquals("TARGET_NOT_ALLOWLISTED", observation.getValue("status").jsonPrimitive.content)
        assertFalse(observation.containsKey("token"))
        assertEquals(2, reads)
    }

    @Test fun aPausedSuccessfulReadCannotPublishActionableTokens() {
        capture = {
            snapshot().copy(
                pauseReason =
                    com.helix.extensions.mobileuse.automation.AutomationPauseReason.TARGET_CHANGED,
            )
        }
        val output = attach()
        assertEquals("UNAVAILABLE", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.getValue("observation").jsonObject.containsKey("token"))
        assertEquals(1, reads)
    }

    @Test fun incompleteStateDoesNotProveTheWholePageSettled() {
        capture = { snapshot(truncated = true) }
        val output = attach()
        assertEquals("UNSETTLED", output.getValue("observationStatus").jsonPrimitive.content)
        assertFalse(output.containsKey("observation"))
        assertEquals(1, reads)
    }

    @Test fun interruptedWaitKeepsDispatchAndTheInterruptWithoutPublishingNodes() {
        sleep = { throw InterruptedException("test") }
        try {
            val output = attach()
            assertEquals("SKIPPED_CANCELLED", output.getValue("observationStatus").jsonPrimitive.content)
            assertFalse(output.containsKey("observation"))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}

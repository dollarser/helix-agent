package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShizukuClickOperationTest {
    private val selector = ShizukuUiSelector("com.example.installer", "android:id/button1", "Update")
    private val xml =
        """<hierarchy rotation="0"><node package="com.example.installer" """ +
            """resource-id="android:id/button1" text="Update" enabled="true" clickable="true" """ +
            """password="false" bounds="[10,20][30,40]"/></hierarchy>"""

    @Test fun successfulOperationChecksObservedPointAndDispatchesOnce() {
        var taps = 0
        var dispatched: Pair<Int, Int>? = null
        val points = mutableListOf<Triple<Int, Int, Int>>()
        val result =
            ShizukuClickOperation({ xml }, { x, y, r ->
                points.add(Triple(x, y, r))
                true
            }, { x, y ->
                assertTrue(x in 19..21)
                assertTrue(y in 29..31)
                dispatched = x to y
                taps++
                true
            }).execute(selector)
        assertEquals("DISPATCHED", result)
        val actual = requireNotNull(dispatched)
        assertEquals(Triple(actual.first, actual.second, 0), points.last())
        assertEquals(1, taps)
    }

    @Test fun revokedOrCancelledAtEachGuardNeverDispatches() {
        for (stopAt in 1..3) {
            var checks = 0
            val result =
                ShizukuClickOperation({
                    xml
                }, { _, _, _ -> ++checks < stopAt }, { _, _ -> error("dispatched") }).execute(selector)
            assertEquals("NOT_DISPATCHED", result)
        }
    }

    @Test fun rejectedPerturbedPointNeverFallsBackToCenterOrResamples() {
        var samples = 0
        var checks = 0
        val random =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Unexpected random source")

                override fun nextInt(
                    from: Int,
                    until: Int,
                ): Int {
                    samples++
                    return from
                }
            }
        val result =
            ShizukuClickOperation(
                { xml },
                { x, y, _ ->
                    checks++
                    if (x == -1) {
                        true
                    } else {
                        assertEquals(19, x)
                        assertEquals(29, y)
                        false
                    }
                },
                { _, _ -> error("A rejected point must not be injected") },
                random,
            ).execute(selector)
        assertEquals("NOT_DISPATCHED", result)
        assertEquals(3, checks)
        assertEquals(2, samples)
    }

    @Test fun changedBoundsOrRotationNeverDispatch() {
        for (changed in listOf(xml.replace("[10,20]", "[11,20]"), xml.replace("rotation=\"0\"", "rotation=\"1\""))) {
            var reads = 0
            val result =
                ShizukuClickOperation({
                    if (++reads ==
                        1
                    ) {
                        xml
                    } else {
                        changed
                    }
                }, { _, _, _ -> true }, { _, _ -> error("dispatched") }).execute(selector)
            assertEquals("TARGET_CHANGED", result)
        }
    }

    @Test fun missingTargetIsNotAnUnknownEffect() {
        val result =
            ShizukuClickOperation({
                xml.replace("Update", "Cancel")
            }, { _, _, _ -> true }, { _, _ -> error("dispatched") }).execute(selector)
        assertEquals("TARGET_NOT_FOUND", result)
    }

    @Test fun guardDeathBeforeInjectionIsARefusal() {
        assertEquals(
            "NOT_DISPATCHED",
            ShizukuClickOperation({
                xml
            }, { _, _, _ -> throw IllegalStateException("dead") }, { _, _ -> error("dispatched") }).execute(selector),
        )
    }

    @Test fun failureOrExceptionAfterInjectionIsUnknownAndNeverRetried() {
        for (throws in listOf(false, true)) {
            var taps = 0
            val result =
                ShizukuClickOperation({ xml }, { _, _, _ -> true }, { _, _ ->
                    taps++
                    check(!throws) { "transport lost" }
                    false
                }).execute(selector)
            assertEquals("OUTCOME_UNKNOWN", result)
            assertEquals(1, taps)
        }
    }

    @Test fun malformedUnknownRemoteOutcomeNeverBecomesSuccessOrSafeFailure() {
        for (value in listOf(null, "PASS", "garbage", "OUTCOME_UNKNOWN")) {
            assertEquals(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN, shizukuActionStatus(value))
        }
        assertEquals(AutomationActionStatus.SUCCEEDED, shizukuActionStatus("DISPATCHED"))
        assertEquals(AutomationActionStatus.ACTION_NOT_DISPATCHED, shizukuActionStatus("NOT_DISPATCHED"))
    }
}

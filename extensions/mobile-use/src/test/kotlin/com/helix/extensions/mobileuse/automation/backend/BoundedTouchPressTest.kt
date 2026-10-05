package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BoundedTouchPressTest {
    @Test fun stationaryPressPreservesTheGuardedPointAndDefaultDisplay() {
        for ((x, y) in listOf(0 to 0, 19 to 29, 9999 to 9999)) {
            val command = boundedTouchPressCommand(x, y, Random(17))
            assertEquals(listOf("/system/bin/input", "touchscreen", "-d", "0", "swipe"), command.take(5))
            assertEquals(listOf(x, y, x, y), command.subList(5, 9).map(String::toInt))
            assertEquals(10, command.size)
        }
    }

    @Test fun durationVariesOnlyWithinTheShortPressBounds() {
        val random = Random(31)
        val durations = List(1000) { boundedTouchPressCommand(20, 30, random).last().toInt() }
        assertTrue(durations.all { it in 60..120 })
        assertEquals(60, durations.min())
        assertEquals(120, durations.max())
    }

    @Test fun invalidPointIsRejectedBeforeAnySampling() {
        val random =
            object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Invalid points must not be sampled")
            }
        assertThrows(IllegalArgumentException::class.java) { boundedTouchPressCommand(-1, 30, random) }
        assertThrows(IllegalArgumentException::class.java) { boundedTouchPressCommand(20, -1, random) }
    }
}

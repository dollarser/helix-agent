package com.helix.extensions.mobileuse.automation.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class PrivilegedLaunchCommandTest {
    private val starting = "Starting: Intent { act=android.intent.action.MAIN cmp=com.example/.MainActivity }\n"

    @Test fun launchCommandKeepsTheExactDisplayAndComponentWithoutWaitingOrForceStopping() {
        assertEquals(
            listOf(
                "/system/bin/am",
                "start",
                "--display",
                "24",
                "--user",
                "current",
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                "-n",
                "com.example/.Main",
            ),
            launchArguments(24, "com.example/.Main"),
        )
    }

    @Test fun acknowledgeDispatchWithoutClaimingTheFirstFrame() {
        assertTrue(launchAcknowledged(0, starting))
        assertTrue(launchAcknowledged(0, starting.replace("MainActivity", "ExceptionActivity")))
        assertTrue(
            launchAcknowledged(
                0,
                starting + "Warning: Activity not started, intent delivered to current instance.\n",
            ),
        )
    }

    @Test fun explicitErrorsOverrideThePrintedIntentEvenWhenExitCodeIsZero() {
        listOf("Error type 3", "Error: Activity not started", "java.lang.SecurityException: denied").forEach {
            assertFalse(launchAcknowledged(0, starting + it))
        }
        assertFalse(launchAcknowledged(1, starting))
        assertFalse(launchAcknowledged(0, ""))
        assertFalse(launchAcknowledged(0, "unrecognized response"))
    }

    @Test fun waitingUsesTheLiveOriginalCallGuardRatherThanASecondTenSecondCap() {
        val process = PendingProcess(150)
        assertTrue(awaitLaunchAcknowledgement(process) { true })
        assertEquals(151, process.polls)
        assertEquals(0, process.destroyCalls)
    }

    @Test fun cancellationOrDeadlineDuringWaitCannotBecomeAnAcknowledgement() {
        val process = PendingProcess(150)
        assertFalse(awaitLaunchAcknowledgement(process) { process.polls < 3 })
        assertEquals(3, process.polls)
        assertFalse(awaitLaunchAcknowledgement(PendingProcess(0)) { false })
    }

    private class PendingProcess(
        private var remaining: Int,
    ) : Process() {
        var polls = 0
        var destroyCalls = 0

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            assertEquals(100, timeout)
            assertEquals(TimeUnit.MILLISECONDS, unit)
            polls++
            return remaining-- <= 0
        }

        override fun waitFor() = 0

        override fun exitValue() = 0

        override fun destroy() {
            destroyCalls++
        }

        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

        override fun getOutputStream() = ByteArrayOutputStream()
    }
}

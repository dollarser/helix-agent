package com.helix.app.foreground

import com.helix.core.model.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the pure-JVM half of the dataSync foreground decision (roadmap HXA-066, architecture doc
 * 5.1): the foreground service is up ONLY while a turn is actively moving data, and the moment the
 * turn waits for the user ([TurnState.WAITING_APPROVAL]) or goes idle (terminal, or no active
 * turn) it stops. The real Android start/stop is device-verified by
 * [DataSyncForegroundServiceDeviceTest]; these tests lock the decision against a recording fake.
 */
class DataSyncForegroundControllerTest {
    private class RecordingLauncher : ForegroundServiceLauncher {
        val starts = AtomicInteger()
        val stops = AtomicInteger()

        override fun start() {
            starts.incrementAndGet()
        }

        override fun stop() {
            stops.incrementAndGet()
        }
    }

    @Test
    fun everyTransportActiveStateStartsTheServiceExactlyOnce() {
        for (state in DataSyncForegroundController.TRANSPORT_ACTIVE) {
            val launcher = RecordingLauncher()
            DataSyncForegroundController(launcher).onTurnState(state)
            assertEquals("state $state should start the service", 1, launcher.starts.get())
            assertEquals(0, launcher.stops.get())
        }
    }

    @Test
    fun repeatedActiveStatesDoNotRestartAnAlreadyRunningService() {
        val launcher = RecordingLauncher()
        val controller = DataSyncForegroundController(launcher)
        controller.onTurnState(TurnState.WAITING_MODEL)
        controller.onTurnState(TurnState.RECEIVING_MODEL)
        assertEquals(1, launcher.starts.get())
        assertEquals(0, launcher.stops.get())
    }

    @Test
    fun waitingForApprovalStopsTheForegroundService() {
        val launcher = RecordingLauncher()
        val controller = DataSyncForegroundController(launcher)
        controller.onTurnState(TurnState.WAITING_MODEL)
        controller.onTurnState(TurnState.WAITING_APPROVAL)
        assertEquals(1, launcher.starts.get())
        assertEquals(1, launcher.stops.get())
    }

    @Test
    fun everyTerminalStateStopsTheForegroundService() {
        for (state in setOf(TurnState.COMPLETED, TurnState.FAILED, TurnState.CANCELLED)) {
            val launcher = RecordingLauncher()
            val controller = DataSyncForegroundController(launcher)
            controller.onTurnState(TurnState.WAITING_MODEL)
            controller.onTurnState(state)
            assertEquals("state $state should stop the service", 1, launcher.stops.get())
        }
    }

    @Test
    fun noActiveTurnNeverStartsAndStopsAServiceThatIsRunning() {
        val launcher = RecordingLauncher()
        val controller = DataSyncForegroundController(launcher)
        controller.onTurnState(null)
        assertEquals(0, launcher.starts.get())
        controller.onTurnState(TurnState.WAITING_MODEL)
        controller.onTurnState(null)
        assertEquals(1, launcher.stops.get())
    }

    @Test
    fun statesThatAreNotAdvancingDoNotStartTheForegroundService() {
        for (state in setOf(TurnState.CREATED, TurnState.CANCELLING, TurnState.INTERRUPTED)) {
            val launcher = RecordingLauncher()
            DataSyncForegroundController(launcher).onTurnState(state)
            assertEquals("state $state should not start the service", 0, launcher.starts.get())
        }
    }

    @Test
    fun resumingAfterApprovalStartsTheServiceAgain() {
        val launcher = RecordingLauncher()
        val controller = DataSyncForegroundController(launcher)
        controller.onTurnState(TurnState.WAITING_MODEL)
        controller.onTurnState(TurnState.WAITING_APPROVAL)
        controller.onTurnState(TurnState.RUNNING_TOOL)
        assertEquals(2, launcher.starts.get())
        assertEquals(1, launcher.stops.get())
    }

    @Test
    fun limitPolicyTripsExactlyAtTheSixHourDataSyncBound() {
        assertFalse(DataSyncLimitPolicy.shouldStop(0L, DataSyncLimitPolicy.DATA_SYNC_LIMIT_MS - 1))
        assertTrue(DataSyncLimitPolicy.shouldStop(0L, DataSyncLimitPolicy.DATA_SYNC_LIMIT_MS))
    }

    @Test
    fun limitPolicyHonoursTheTwentyFourHourCeilingAndTheDefaultBound() {
        assertFalse(
            DataSyncLimitPolicy.shouldStop(
                0L,
                DataSyncLimitPolicy.CEILING_LIMIT_MS - 1,
                DataSyncLimitPolicy.CEILING_LIMIT_MS,
            ),
        )
        assertTrue(
            DataSyncLimitPolicy.shouldStop(
                0L,
                DataSyncLimitPolicy.CEILING_LIMIT_MS,
                DataSyncLimitPolicy.CEILING_LIMIT_MS,
            ),
        )
        // The default 6 h bound trips well before the 24 h ceiling.
        assertTrue(DataSyncLimitPolicy.shouldStop(0L, DataSyncLimitPolicy.CEILING_LIMIT_MS))
    }
}

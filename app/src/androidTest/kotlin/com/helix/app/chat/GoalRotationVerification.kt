package com.helix.app.chat

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.test.core.app.ActivityScenario
import com.helix.app.AppContainer
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Rotate the real Activity while the production Goal owns a held model socket. */
internal fun verifyGoalRotation(
    container: AppContainer,
    goal: String,
    session: String,
    server: LoopbackModelServer,
) {
    val runs =
        container.storage.goalRuns
            .listByGoal(goal)
            .map { it.id }
    val turns =
        container.storage.turns
            .listBySession(session)
            .map { it.id }
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        var originalRequest = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var originalOrientation = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity {
            originalRequest = it.requestedOrientation
            originalOrientation = it.resources.configuration.orientation
        }
        val target =
            if (originalOrientation == Configuration.ORIENTATION_PORTRAIT) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        try {
            scenario.onActivity {
                it.requestedOrientation =
                    if (target == Configuration.ORIENTATION_LANDSCAPE) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
            }
            awaitOrientation(scenario, target)
            assertEquals(
                runs,
                container.storage.goalRuns
                    .listByGoal(goal)
                    .map { it.id },
            )
            assertEquals(
                turns,
                container.storage.turns
                    .listBySession(session)
                    .map { it.id },
            )
            assertEquals(
                "RUNNING",
                container.storage.goals
                    .resolve(goal)
                    .state,
            )
            assertEquals(1, server.heldStreams.get())
            assertFalse(server.heldStreamDisconnected.get())
        } finally {
            scenario.onActivity { it.requestedOrientation = originalRequest }
            awaitOrientation(scenario, originalOrientation)
        }
    }
}

private fun awaitOrientation(
    scenario: ActivityScenario<MainActivity>,
    expected: Int,
) {
    val deadline = android.os.SystemClock.elapsedRealtime() + 10000
    var actual = Configuration.ORIENTATION_UNDEFINED
    while (actual != expected) {
        scenario.onActivity { actual = it.resources.configuration.orientation }
        assertTrue("Activity orientation did not reach $expected", android.os.SystemClock.elapsedRealtime() < deadline)
        Thread.sleep(25)
    }
}

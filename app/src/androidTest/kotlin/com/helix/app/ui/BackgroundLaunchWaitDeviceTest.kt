package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.proot.BackgroundJobAction
import com.helix.app.proot.BackgroundJobUi
import com.helix.app.proot.CommandDetailState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class BackgroundLaunchWaitDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eligibleLaunchShowsScopedBackgroundAction() {
        val job = BackgroundJobUi("original", "turn", "session", "Job", CommandDetailState.UNKNOWN, true, true)
        var action: BackgroundJobAction? = null
        compose.setContent {
            MaterialTheme {
                BackgroundJobControls(job, null) { original, requested ->
                    assertEquals(job, original)
                    action = requested
                }
            }
        }
        compose.onNodeWithTag("tasks-job-background-original").assertIsDisplayed().performClick()
        assertEquals(BackgroundJobAction.BACKGROUND, action)
    }

    @Test fun ordinaryBackgroundJobDoesNotOfferLaunchPromotion() {
        val job = BackgroundJobUi("original", "turn", "session", "Job", CommandDetailState.UNKNOWN, true)
        compose.setContent { MaterialTheme { BackgroundJobControls(job, null) { _, _ -> error("no action") } } }
        compose.onNodeWithTag("tasks-job-background-original").assertDoesNotExist()
    }
}

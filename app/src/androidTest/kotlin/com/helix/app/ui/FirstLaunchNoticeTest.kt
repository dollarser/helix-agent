package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HXA-028 first-launch privacy notice (matrix: “首次启动隐私说明”). The notice
 * blocks the whole shell until explicitly acknowledged; the acknowledgement
 * persists, and a data reset (here simulated through the store's test seam)
 * brings it back (ADR-0006: fresh install / reset → STANDARD + first-launch
 * flow).
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchNoticeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun noticeBlocksShellUntilDismissedAndStaysDismissed() {
        // Re-arm the fresh-install state (the flag persists in SharedPreferences
        // across instrumented runs).
        composeRule.container().firstLaunch.reset()
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()

        // Fresh state: the notice blocks the shell.
        composeRule.onNodeWithTag("first-launch-notice").assertIsDisplayed()
        composeRule.onNodeWithTag("first-launch-continue").performClick()
        composeRule.waitForIdle()

        // Dismissed: the shell is shown and the acknowledgement persisted.
        composeRule.onNodeWithTag("screen-sessions").assertIsDisplayed()
        assertTrue(composeRule.container().firstLaunch.noticeSeen)

        // A new activity (e.g. rotation / relaunch) does not re-show the notice.
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("first-launch-notice").assertIsNotDisplayed()
        composeRule.onNodeWithTag("screen-sessions").assertIsDisplayed()
    }
}

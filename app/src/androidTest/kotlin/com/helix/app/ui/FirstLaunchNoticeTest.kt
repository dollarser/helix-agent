package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

    private fun screenshot(name: String) {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation()
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(350, 5_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val target = java.io.File(instrumentation.targetContext.filesDir, "validation/startup-ui/$name.png")
        target.parentFile?.mkdirs()
        try {
            target.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun noticeBlocksShellUntilDismissedAndStaysDismissed() {
        // Re-arm the fresh-install state (the flag persists in SharedPreferences
        // across instrumented runs).
        composeRule.container().firstLaunch.reset()
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()

        // Fresh state: the notice blocks the shell.
        composeRule.onNodeWithTag("first-launch-notice").assertIsDisplayed()
        screenshot("notice")
        composeRule.onNodeWithTag("first-launch-continue").performClick()
        composeRule.waitForIdle()

        // Optional permissions are separate from notice acknowledgement and can be skipped.
        composeRule.onNodeWithTag("startup-permissions").assertIsDisplayed()
        screenshot("permissions")
        composeRule.runOnUiThread { composeRule.activity.recreate() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("startup-permissions").assertIsDisplayed()
        composeRule.onNodeWithTag("startup-permissions-continue").performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag("screen-sessions").fetchSemanticsNodes().isNotEmpty()
        }
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

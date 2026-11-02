package com.helix.app.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Actual Activity replacement with the same fixed large history as the renderer stress. */
class ConversationVolumeRotationDeviceTest {
    private val data = ConversationVolumeData()

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = { data.Content() }
        }

    @After fun clearFixture() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun rotationKeepsReadingHistoryAndExpandedGroupUntilExplicitLatest() {
        val original = compose.activity
        val orientation = original.requestedOrientation
        try {
            compose.onNodeWithTag("chat-search-toggle").performClick()
            compose.onNodeWithTag("chat-search-input").performTextReplacement("synthetic-12-4.txt")
            compose.onNodeWithTag("tool-row-c-12-4").assertIsDisplayed()
            compose.onNodeWithTag("chat-timeline").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100f) }
            compose.onNodeWithTag("chat-search-close").performClick()
            screenshot("volume-reading")
            compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
            checkpoint("VOLUME_READING_HISTORY_READY")
            val start = SystemClock.elapsedRealtime()
            compose.runOnUiThread { original.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(15_000) {
                compose.activity !== original &&
                    compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
            assertNotSame(original, compose.activity)
            checkpoint("VOLUME_ACTIVITY_REPLACED")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-scroll-latest").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
            compose.onNodeWithTag("tool-row-c-12-4").assertExists()
            val elapsed = SystemClock.elapsedRealtime() - start
            screenshot("volume-landscape")
            compose.onNodeWithTag("chat-scroll-latest").performClick()
            compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
            compose.onNodeWithTag("artifact-file-row-artifact-t-199").assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.getExternalFilesDir(null), "stability-volume").also { it.mkdirs() }
            File(directory, "rotation.json").writeText("{\"actualActivityReplacement\":true,\"rotationMs\":$elapsed}")
        } finally {
            compose.runOnUiThread { compose.activity.requestedOrientation = orientation }
        }
    }

    private fun checkpoint(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "$value\n")
            },
        )
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "stability-volume").also { it.mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

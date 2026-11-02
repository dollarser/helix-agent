package com.helix.app.ui

import android.os.Debug
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Bounded renderer stress, not a Room startup, shipping benchmark or battery endurance claim. */
class ConversationVolumeDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val data = ConversationVolumeData()
    private val measurements = linkedMapOf<String, Long>()

    @Test fun largeHistorySearchGroupingReadingAndRestoration() {
        val restoration = StateRestorationTester(compose)
        measure("firstCompositionMs") {
            restoration.setContent { data.Content() }
        }
        compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
        measure("searchAndRevealMs") {
            compose.onNodeWithTag("chat-search-toggle").performClick()
            compose.onNodeWithTag("chat-search-input").performTextReplacement("synthetic-100-4.txt")
            compose.onNodeWithTag("tool-row-c-100-4").assertIsDisplayed()
        }
        compose.onNodeWithTag("chat-search-close").performClick()
        measure("collapseExpandMs") {
            compose.onNodeWithTag("tool-group-toggle-c-100-0").performScrollTo().performClick()
            compose.onNodeWithTag("tool-row-c-100-4").assertDoesNotExist()
            compose.onNodeWithTag("tool-group-toggle-c-100-0").performClick()
            compose.onNodeWithTag("tool-row-c-100-4").assertExists()
        }
        measure("thirtyHistoryScrollsMs") {
            repeat(30) {
                val before = scrollProgress()
                compose
                    .onNodeWithTag("chat-timeline")
                    .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -500f) }
                assertTrue("Each measured scroll must move through history", scrollProgress() < before)
            }
        }
        compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
        val beforeRestore = scrollProgress().toInt()
        measure("historyRestorationMs") { restoration.emulateSavedInstanceStateRestore() }
        compose.onNodeWithTag("chat-scroll-latest").assertIsDisplayed()
        assertEquals(beforeRestore, scrollProgress().toInt())
        measure("returnLatestMs") { compose.onNodeWithTag("chat-scroll-latest").performClick() }
        compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
        measure("saveableRestorationMs") { restoration.emulateSavedInstanceStateRestore() }
        compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
        saveMetrics()
    }

    private fun scrollProgress() =
        compose
            .onNodeWithTag("chat-timeline")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun measure(
        name: String,
        action: () -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        action()
        compose.waitForIdle()
        measurements[name] = SystemClock.elapsedRealtime() - start
    }

    private fun saveMetrics() {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        measurements["processPssKb"] = memory.totalPss.toLong()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "stability-volume").also { it.mkdirs() }
        File(directory, "metrics.json").writeText(
            org.json
                .JSONObject(measurements as Map<*, *>)
                .put("messages", 400)
                .put("tools", 1000)
                .put("images", 25)
                .put("distinctImageContents", 25)
                .put("completedHistoryScrolls", 30)
                .put("artifactMetadataRows", 200)
                .toString(2),
        )
    }
}

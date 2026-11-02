package com.helix.app.ui

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import org.json.JSONObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Seed and verify are separate instrumentation processes; the host additionally force-stops between phases. */
class PersistentConversationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun persistentHistoryAndImages() {
        val container = compose.container()
        val app = compose.activity.application as HelixApplication
        val fixture = PersistentConversationData(app)
        val phase = InstrumentationRegistry.getArguments().getString("persistent.phase")
        val metrics = JSONObject()
        if (phase == "seed" || phase == "prepare") {
            if (phase == "seed") {
                compose.resetDeterministicUiState()
                measure(metrics, "seedMs") { fixture.seed() }
            } else {
                fixture.verify()
            }
            container.chatService.openSession(fixture.session)
            compose.waitUntil(30_000) {
                container.chatService.screen.value.messages.any {
                    it.id ==
                        "${fixture.session}-a-1999"
                }
            }
        } else {
            require(phase == "verify") { "Choose the explicit seed, prepare or verify phase" }
            assertNotEquals(fixture.manifest.getInt("seedPid"), Process.myPid())
            measure(metrics, "postActivityRuleRestorationWaitMs") {
                compose.dismissFirstLaunchIfNeeded()
                compose.waitUntil(30_000) {
                    container.chatService.screen.value.openSessionId == fixture.session &&
                        container.chatService.screen.value.messages
                            .any { it.id == "${fixture.session}-a-1999" }
                }
                compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
            }
            fixture.verify()
            exercise(fixture, metrics)
        }
        metrics.put("pssKb", memory())
        fixture.report(requireNotNull(phase), metrics)
    }

    private fun exercise(
        fixture: PersistentConversationData,
        metrics: JSONObject,
    ) {
        val chat = compose.container().chatService
        metrics.put("initialVisibleTexts", chat.screen.value.messages.size)
        metrics.put("initialVisibleTools", chat.screen.value.toolTimeline.size)
        assertTrue(chat.screen.value.messages.size < 1000)
        measure(metrics, "threeOlderPagesMs") {
            repeat(3) {
                val before = chat.screen.value.messages.size
                chat.loadOlderMessages()
                compose.waitUntil(30_000) { chat.screen.value.messages.size > before }
            }
        }
        var peak = memory()
        measure(metrics, "tenSearchesAndImagePreviewsMs") {
            repeat(10) { number ->
                val index = number * 180
                chat.findHistoryMessage("PERSISTENT_NEEDLE_$index Synthetic", null, null)
                compose.waitUntil(30_000) {
                    chat.screen.value.focusedHistoryMessage == "${fixture.session}-u-$index" &&
                        chat.screen.value.messages
                            .any { it.id == "${fixture.session}-u-$index" }
                }
                compose.onNodeWithTag("chat-image-${fixture.session}-image-$index").performScrollTo().performClick()
                compose.onNodeWithTag("chat-image-preview").assertIsDisplayed()
                compose.waitUntil(10_000) {
                    compose
                        .onAllNodes(
                            hasTestTag("chat-image-loaded") and hasAnyAncestor(hasTestTag("chat-image-preview")),
                            true,
                        ).fetchSemanticsNodes()
                        .isNotEmpty()
                }
                peak = maxOf(peak, memory())
                compose.onNodeWithTag("chat-image-close").performClick()
            }
        }
        metrics.put("peakImagePssKb", peak)
        measure(metrics, "returnLatestMs") {
            chat.showLatestMessages()
            compose.waitUntil(30_000) {
                chat.screen.value.messages
                    .any { it.id == "${fixture.session}-a-1999" }
            }
            compose.onNodeWithTag("chat-timeline-end").assertIsDisplayed()
        }
    }

    private fun memory() = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss

    private fun measure(
        metrics: JSONObject,
        key: String,
        action: () -> Unit,
    ) {
        val start = SystemClock.elapsedRealtime()
        action()
        compose.waitForIdle()
        metrics.put(key, SystemClock.elapsedRealtime() - start)
    }
}

package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.connector.ConnectorSessionPanelState
import com.helix.app.connector.PluginSessionPicker
import com.helix.app.plugin.PluginSessionRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.helix.ui.shared.R as SharedR

/** Controlled local reads exercise the production picker, including the unresolved first frame. */
class LocalPickerPresentationDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val row = PluginSessionRow("fixture", "Example Plugin", false, false, true, true, readyComponents = 1)
    private val tag = "connector-session-sheet"

    @Test fun initialReadCreatesNoPlaceholderAndOpensOnlyResolvedContent() {
        var writes = 0
        val state = ConnectorSessionPanelState({ "A" to listOf(row) }, { _, _ -> writes++ }) { true }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        compose.onNodeWithTag(tag).assertDoesNotExist()
        compose.onNodeWithTag("plugin-session-loading").assertDoesNotExist()
        runBlocking { state.refresh() }
        compose.onNodeWithTag(tag).assertExists()
        awaitPicker()
        compose.onNodeWithText("Example Plugin").assertIsDisplayed()
        compose.onNodeWithTag("plugin-session-loading").assertDoesNotExist()
        assertEquals(0, writes)
    }

    @Test fun refreshKeepsTheSameWindowAndContentWithoutLoadingResize() {
        var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        val state =
            ConnectorSessionPanelState({
                gate?.let {
                    entered.countDown()
                    check(it.await(5, TimeUnit.SECONDS))
                }
                "A" to listOf(row)
            }, { _, _ -> error("No selection writes expected") }) { true }
        runBlocking { state.refresh() }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        awaitPicker()
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CountDownLatch(1)
        try {
            gate = release
            val job = compose.runOnIdle { scope.launch { state.refresh() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            awaitPicker()
            compose.onNodeWithText("Example Plugin").assertIsDisplayed()
            compose.onNodeWithTag("connector-session-fixture").assertIsNotEnabled()
            compose.onNodeWithTag("plugin-session-loading").assertDoesNotExist()
            assertEquals(before.id, compose.onNodeWithTag(tag).fetchSemanticsNode().id)
            assertEquals(before.boundsInRoot, compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
            release.countDown()
            runBlocking { job.join() }
            assertFalse(state.failed)
            assertEquals(before.id, compose.onNodeWithTag(tag).fetchSemanticsNode().id)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test fun failureOpensRetryAndRecoveryRetainsTheSameWindow() {
        var fail = true
        val state =
            ConnectorSessionPanelState({
                check(!fail)
                "A" to listOf(row)
            }, { _, _ -> }) { true }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        runBlocking { state.refresh() }
        compose.waitUntil(10_000) {
            compose.onNodeWithText(app.getString(R.string.plugin_selection_load_failed)).isDisplayed()
        }
        val before = compose.onNodeWithTag(tag).fetchSemanticsNode().id
        compose.onNodeWithText(app.getString(R.string.plugin_selection_load_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.connector_session_empty)).assertDoesNotExist()
        fail = false
        compose.onNodeWithText(app.getString(SharedR.string.chat_retry)).performClick()
        compose.waitUntil(10_000) { !state.loading && !state.failed }
        awaitPicker()
        compose.onNodeWithText("Example Plugin").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag(tag).fetchSemanticsNode().id)
    }

    @Test fun resolvedEmptyListStillHasItsExplicitEmptyState() {
        val state = ConnectorSessionPanelState({ "A" to emptyList() }, { _, _ -> }) { true }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        runBlocking { state.refresh() }
        compose.onNodeWithTag(tag).assertExists()
        compose.onNodeWithText(app.getString(R.string.connector_session_empty)).assertIsDisplayed()
    }

    @Test fun cancelledFirstReadCannotCreateAnEmptyOrFailedPlaceholder() {
        val state = ConnectorSessionPanelState({ throw CancellationException() }, { _, _ -> }) { true }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        assertThrows(CancellationException::class.java) { runBlocking { state.refresh() } }
        compose.onNodeWithTag(tag).assertDoesNotExist()
        assertFalse(state.failed)
    }

    @Test fun changedTargetCannotOpenALateDialogWhenItsReadCompletes() {
        var current by androidx.compose.runtime.mutableStateOf(true)
        val state = ConnectorSessionPanelState({ "A" to listOf(row) }, { _, _ -> }) { current }
        compose.setContent { MaterialTheme { PluginSessionPicker(state, { _, _ -> }, {}) } }
        compose.runOnIdle { current = false }
        runBlocking { state.refresh() }
        assertTrue(state.loaded)
        compose.onNodeWithTag(tag).assertDoesNotExist()
    }

    private fun awaitPicker() {
        compose.waitUntil(10_000) { compose.onNodeWithText("Example Plugin").isDisplayed() }
    }
}

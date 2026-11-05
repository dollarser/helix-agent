package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.R
import com.helix.app.connector.MOBILE_USE_PLUGIN_ID
import com.helix.app.connector.MobileUseSessionState
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.plugin.PluginSetupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** The production prompt waits for local facts, without selecting or granting anything. */
class MobileUseSessionPromptDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val ready =
        PluginSessionRow(MOBILE_USE_PLUGIN_ID, "Mobile Use", false, false, true, true, readyComponents = 1)

    @Test fun realEmptyConversationReadsLocalStatusBeforeOpeningAndDoesNotSelect() {
        val c = app.appContainer
        val id = "mobile-prompt-${UUID.randomUUID()}"
        c.storage.sessions.create(id, "Synthetic prompt fixture", null, null, System.currentTimeMillis())
        val original = c.pluginService.catalog.selected(id)
        try {
            compose.setContent {
                MaterialTheme { MobileUseSessionDialog(c.pluginService, id, id, { true }, {}, {}, {}) }
            }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("mobile-use-dialog").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("mobile-use-dialog-confirm").assertIsEnabled()
            compose.onNodeWithText(app.getString(R.string.tasks_commands_loading)).assertDoesNotExist()
            assertEquals(original, c.pluginService.catalog.selected(id))
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(app.cacheDir, "mobile-use-prompt-ready.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            image.recycle()
        } finally {
            c.storage.deleteSessionPermanently(id)
        }
    }

    @Test fun cancelledInitialReadCannotPresentAnUnresolvedPluginAsUnavailable() {
        val state = MobileUseSessionState({ throw CancellationException("synthetic cancelled read") }, {}, { true })
        compose.setContent { MaterialTheme { MobileUseSessionPrompt(state, { true }, {}, {}) } }
        runBlocking {
            try {
                state.refresh()
            } catch (_: CancellationException) {
                // Cancellation leaves no resolved facts and must not become a permission prompt.
            }
        }
        compose.onNodeWithTag("mobile-use-dialog").assertDoesNotExist()
    }

    @Test fun firstReadOpensOnlyTheResolvedPromptAndRequiresAnExplicitClick() {
        var confirms = 0
        var selections = 0
        val state = MobileUseSessionState({ ready }, { selections++ }, { true })
        compose.setContent { MaterialTheme { MobileUseSessionPrompt(state, { true }, { confirms++ }, {}) } }
        compose.onNodeWithTag("mobile-use-dialog").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.tasks_commands_loading)).assertDoesNotExist()
        runBlocking { state.refresh() }
        compose.onNodeWithText(app.getString(R.string.mobile_use_session_hint)).assertIsDisplayed()
        assertEquals(0, confirms)
        assertEquals(0, selections)
        compose.onNodeWithTag("mobile-use-dialog-confirm").assertIsEnabled().performClick()
        assertEquals(1, confirms)
        assertEquals(0, selections)
    }

    @Test fun missingPermissionOpensDirectlyWithTheRepairAction() {
        var repairs = 0
        val state = MobileUseSessionState({ ready.copy(setupReason = PluginSetupReason.MOBILE_BACKEND) }, {}, { true })
        compose.setContent { MaterialTheme { MobileUseSessionPrompt(state, { true }, { repairs++ }, {}) } }
        compose.onNodeWithTag("mobile-use-dialog").assertDoesNotExist()
        runBlocking { state.refresh() }
        compose.onNodeWithText(app.getString(R.string.plugin_setup_mobile_backend)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.mobile_use_go_authorize)).assertIsDisplayed().performClick()
        assertEquals(1, repairs)
        compose.onNodeWithText(app.getString(R.string.tasks_commands_loading)).assertDoesNotExist()
    }

    @Test fun readFailureKeepsRetryAndExpiredRequestsCannotOpenTheDialog() {
        var current = true
        var fail = true
        var retries = 0
        val state =
            MobileUseSessionState({ if (fail) error("synthetic local read failure") else ready }, {}, { current })
        compose.setContent { MaterialTheme { MobileUseSessionPrompt(state, { current }, { retries++ }, {}) } }
        runBlocking { state.refresh() }
        compose.onNodeWithText(app.getString(R.string.connector_session_failed)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.chat_retry)).performClick()
        assertEquals(1, retries)
        fail = false
        runBlocking { state.refresh() }
        compose.onNodeWithText(app.getString(R.string.mobile_use_session_hint)).assertIsDisplayed()
        current = false
        runBlocking { state.refresh() }
        compose.onNodeWithTag("mobile-use-dialog").assertDoesNotExist()
    }
}

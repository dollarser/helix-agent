package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.helix.app.MainActivity
import com.helix.app.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Read-only navigation and draft inspection; never clears providers or workspace contents. */
class NavigationLayoutDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsProviderDraftFilesAndBrowserRemainReachable() {
        compose.resetDeterministicUiState()
        try {
            prepareChatLayoutLanguage(compose)
            compose.navigateTo("settings")
            compose
                .onNodeWithTag("provider-add")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitForIdle()
            captureChatLayout(compose.activity, "provider-picker")
            compose
                .onNodeWithTag("provider-template-ollama")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("provider-template-guidance").assertTextEquals(
                compose.activity.getString(
                    R.string.provider_template_note,
                    compose.activity.getString(R.string.provider_note_ollama),
                ),
            )
            captureChatLayout(compose.activity, "provider-form")
            compose.onNodeWithTag("provider-form-model").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("provider-form-cancel").assertIsDisplayed().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("provider-form-dialog").assertDoesNotExist()
            verifyFiles()
            visit("browser", "browser-url-field")
            listOf("browser-clear-cookies", "browser-clear-cache", "browser-clear-history").forEach {
                assertWithin("browser", it)
            }
            visit("sessions", "chat-new-session")
        } finally {
            prepareChatLayoutLanguage(compose, restore = true)
        }
    }

    private fun verifyFiles() {
        compose.navigateTo("files")
        val config = compose.activity.resources.configuration
        val compact = config.screenHeightDp <= 640 || config.fontScale >= 1.3f
        if (compact) compose.onNodeWithTag("files-controls-open").assertIsDisplayed().performClick()
        listOf("files-source-current", "files-view-grid", "files-import-open", "files-newfolder").forEach {
            val node = compose.onNodeWithTag(it)
            if (compact) node.performScrollTo()
            node.assertIsDisplayed()
        }
        captureChatLayout(compose.activity, "files-options")
        if (compact) compose.onNodeWithTag("files-controls-close").performClick()
        compose.waitForIdle()
        compose
            .onNodeWithTag("files-entry-work")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        captureChatLayout(compose.activity, "files")
    }

    private fun visit(
        route: String,
        tag: String,
    ) {
        compose.navigateTo(route)
        compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.waitForIdle()
        captureChatLayout(compose.activity, route)
        assertWithin(route, tag)
    }

    private fun assertWithin(
        route: String,
        tag: String,
    ) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val node = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val viewport = compose.onNodeWithTag("screen-$route").getUnclippedBoundsInRoot()
        assertTrue("$tag must fit horizontally", node.left >= viewport.left && node.right <= viewport.right)
        assertTrue("$tag must fit vertically", node.top >= viewport.top && node.bottom <= viewport.bottom)
    }
}

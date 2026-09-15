package com.helix.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.helix.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * The P0-B Capability Center (doc section 11 / PX-04): the drawer's capabilities destination
 * opens a real first-class page listing every runtime capability with a live status, and a
 * row expands to what it enables / why / current scope plus honest Test / Repair / Disable
 * actions. On a clean consumer build: files (SAF) read Ready, root reads Unavailable, and the
 * MCP row reports its connected-endpoint count.
 */
class CapabilitiesCenterDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun capabilitiesPageListsLiveRowsAndExpandsActions() {
        runBlocking {
            compose.resetDeterministicUiState()
            compose.navigateTo("capabilities")
            compose.onNodeWithTag("screen-capabilities").assertExists()

            // All eight capability rows render.
            val keys =
                listOf(
                    "files",
                    "browser",
                    "notifications",
                    "calendar",
                    "accessibility",
                    "runtime",
                    "root",
                    "mcp",
                )
            compose.waitUntil(10_000) {
                keys.all { key -> compose.onAllNodesWithTag("capability-$key").fetchSemanticsNodes().isNotEmpty() }
            }

            // Live statuses on a clean consumer build: files (SAF) is a platform feature so it
            // reads Ready, root reads Unavailable, and the MCP row reports its connected count.
            // The leaf tags sit under the clickable card, so they come from the unmerged tree.
            compose.onNodeWithTag("capability-files-status", useUnmergedTree = true).assertTextEquals("就绪")
            compose.onNodeWithTag("capability-root-status", useUnmergedTree = true).assertTextEquals("不可用")
            compose.onNodeWithTag("capability-mcp-status", useUnmergedTree = true).assertTextEquals("0 个已连接")
            compose.onNodeWithTag("capability-browser-status", useUnmergedTree = true).assertIsDisplayed()

            // Tapping a row expands what it enables / why / current scope and its actions.
            compose.onNodeWithTag("capability-notifications", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("capability-notifications-detail", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("capability-notifications-what", useUnmergedTree = true).assertIsDisplayed()

            // The notifications row offers a Test action; tapping it posts a notification
            // and does not crash or leave the page.
            compose.onNodeWithTag("capability-notifications-test", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("screen-capabilities").assertExists()
        }
    }
}

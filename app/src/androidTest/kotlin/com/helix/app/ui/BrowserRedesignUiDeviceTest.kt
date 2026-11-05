package com.helix.app.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserRedesignUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun taskPageReturnsOriginalSessionAndRequiresExplicitReopenAfterOwnerReplacement() {
        val context = browserTestContext()
        lateinit var controller: BrowserController
        lateinit var owner: BrowserViewOwner
        lateinit var activity: android.app.Activity
        var returned: String? = null
        compose.runOnUiThread { controller = BrowserController(context) }
        runBlocking { controller.sessionReady.await() }
        compose.setContent {
            activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
            androidx.compose.runtime.DisposableEffect(Unit) {
                owner = BrowserViewOwner(activity)
                controller.attach(owner)
                onDispose {
                    controller.detach(owner)
                    owner.destroy()
                }
            }
            MaterialTheme { BrowserScreen(controller) { returned = it } }
        }
        lateinit var task: String
        compose.runOnUiThread {
            task = controller.openTab("data:text/html,<body>synthetic-task</body>", "original-session").tabId
        }
        compose.onNodeWithTag("browser-origin-session").performClick()
        compose.runOnIdle { assertEquals("original-session", returned) }
        compose.runOnUiThread {
            controller.detach(owner)
            owner.destroy()
            owner = BrowserViewOwner(activity)
            controller.attach(owner)
            assertNull(controller.hostView(task))
        }
        compose.onNodeWithTag("browser-reopen-page").assertIsDisplayed()
        compose.runOnIdle { assertNull(controller.hostView(task)) }
        val generation = controller.tab(task)!!.navigationGeneration
        compose.onNodeWithTag("browser-reopen-page").performClick()
        // Compose idle does not await WebView's asynchronous page-commit callback.
        compose.waitUntil(10_000) {
            controller.state.value.tabs
                .single { it.id == task }
                .navigationGeneration > generation
        }
        compose.runOnIdle {
            assertNotNull(controller.hostView(task))
            assertTrue(controller.tab(task)!!.navigationGeneration > generation)
            assertEquals("original-session", controller.tab(task)!!.originSessionId)
        }
    }

    @Test
    fun downloadsMenuOpensTheActualEmptyList() {
        val context = browserTestContext()
        lateinit var controller: BrowserController
        compose.runOnUiThread { controller = BrowserController(context) }
        runBlocking { controller.sessionReady.await() }
        compose.setContent { MaterialTheme { BrowserScreen(controller) } }
        compose.onNodeWithTag("browser-menu").performClick()
        compose
            .onNodeWithText(
                context.getString(com.helix.feature.browser.R.string.browser_menu_downloads),
            ).performClick()
        compose
            .onNodeWithText(context.getString(com.helix.feature.browser.R.string.browser_downloads_empty))
            .assertIsDisplayed()
    }

    @Test
    fun fullTabSwitcherDisablesNormalAndNoHistoryCreation() {
        val context = browserTestContext()
        lateinit var controller: BrowserController
        compose.runOnUiThread {
            controller = BrowserController(context)
        }
        runBlocking { controller.sessionReady.await() }
        compose.runOnUiThread {
            repeat(8) { controller.newTab() }
        }
        compose.setContent { MaterialTheme { BrowserScreen(controller) } }
        compose.onNodeWithTag("browser-tabs").performClick()
        compose.onNodeWithTag("browser-tab-new").assertIsNotEnabled()
        compose.onNodeWithTag("browser-tab-new-no-history").assertIsNotEnabled()
        compose
            .onNodeWithText(
                context.getString(com.helix.feature.browser.R.string.browser_tab_incognito),
                substring = true,
            ).assertIsDisplayed()
    }
}

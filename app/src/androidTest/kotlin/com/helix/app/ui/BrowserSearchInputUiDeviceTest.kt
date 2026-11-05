package com.helix.app.ui

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.engine.SearchEngines
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Exercises the home entry and the actual IME without modifying the daily browser's storage. */
class BrowserSearchInputUiDeviceTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var controller: BrowserController
    private lateinit var activity: Activity
    private lateinit var tabId: String
    private var exited = false

    @Test
    fun homeSearchFocusesAddressAndImeSubmitsTypedQuery() {
        launchBrowser()
        compose.onNodeWithTag("browser-go").assertIsNotEnabled()
        compose.onNodeWithTag("browser-home-search").performClick()
        compose.onNodeWithTag("browser-url-field").assertIsFocused()
        awaitKeyboard(true)
        // Digits commit directly with either Latin or the daily emulator's pinyin IME.
        InstrumentationRegistry.getInstrumentation().sendStringSync("12345")
        compose.onNodeWithTag("browser-url-field").assertTextEquals("12345")
        compose.onNodeWithTag("browser-url-field").performTextInput(" 中文")
        compose.onNodeWithTag("browser-url-field").assertTextEquals("12345 中文")
        compose.onNodeWithTag("browser-url-field").performImeAction()
        awaitUrl("https://www.bing.com/search?q=12345+%E4%B8%AD%E6%96%87")
        compose.onNodeWithTag("browser-url-field").assertIsNotFocused()
        awaitKeyboard(false)
        Espresso.pressBack()
        compose.runOnIdle { org.junit.Assert.assertTrue(exited) }
        awaitUrl("https://www.bing.com/search?q=12345+%E4%B8%AD%E6%96%87")
    }

    @Test
    fun engineSwitchKeepsDraftAndMenuBackCancelsWithoutSearching() {
        launchBrowser()
        compose.onNodeWithTag("browser-search-engine").performClick()
        compose.onNodeWithTag("browser-search-engine-duckduckgo").performClick()
        compose.waitUntil(5_000) { controller.preferences.value.searchEngineId == "duckduckgo" }
        compose.onNodeWithTag("browser-home-search").performClick()
        compose.onNodeWithTag("browser-url-field").performTextInput("draft query")
        awaitKeyboard(true)
        Espresso.pressBack()
        awaitKeyboard(false)
        compose.onNodeWithTag("browser-url-field").assertTextEquals("draft query")
        awaitUrl(BrowserTabController.ABOUT_BLANK)
        compose.onNodeWithTag("browser-search-engine").performClick()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals("duckduckgo", controller.preferences.value.searchEngineId) }
        compose.onNodeWithTag("browser-url-field").assertTextEquals("draft query")
        awaitUrl(BrowserTabController.ABOUT_BLANK)
        compose.onNodeWithTag("browser-home-search").performClick()
        awaitKeyboard(true)
        compose.onNodeWithTag("browser-go").performClick()
        awaitUrl("https://duckduckgo.com/?q=draft+query")
        awaitKeyboard(false)
    }

    @Test
    fun addressBarPaddingAcceptsFocusAndClearDoesNotNavigate() {
        launchBrowser()
        compose.onNodeWithTag("browser-url-field").performTouchInput {
            click(Offset(width / 2f, 2f))
        }
        compose.onNodeWithTag("browser-url-field").assertIsFocused()
        awaitKeyboard(true)
        compose.onNodeWithTag("browser-url-field").performTextInput("cancelled query")
        compose.onNodeWithTag("browser-url-field").performTextClearance()
        compose.onNodeWithTag("browser-go").assertIsNotEnabled()
        Espresso.pressBack()
        awaitKeyboard(false)
        awaitUrl(BrowserTabController.ABOUT_BLANK)
        compose.onNodeWithTag("browser-home-search").performClick()
        awaitKeyboard(true)
    }

    private fun launchBrowser() {
        val context = browserTestContext()
        compose.runOnUiThread { controller = BrowserController(context) }
        runBlocking { controller.sessionReady.await() }
        compose.setContent {
            activity = LocalContext.current as Activity
            DisposableEffect(Unit) {
                val owner = BrowserViewOwner(activity)
                controller.attach(owner)
                tabId = controller.openTab(BrowserTabController.ABOUT_BLANK).tabId
                // Assert generated search URLs locally; no external search engine is contacted.
                checkNotNull(controller.hostView(tabId)).settings.blockNetworkLoads = true
                onDispose {
                    controller.detach(owner)
                    owner.destroy()
                }
            }
            MaterialTheme { BrowserScreen(controller, onExitBrowser = { exited = true }) }
        }
        compose.runOnUiThread { controller.setSearchEngine(SearchEngines.BING.id) }
        compose.waitUntil(5_000) { controller.preferences.value.searchEngineId == SearchEngines.BING.id }
        compose.waitUntil(5_000) { controller.tab(tabId)?.isLoading == false }
    }

    private fun awaitUrl(expected: String) {
        compose.waitUntil(5_000) { controller.tab(tabId)?.url == expected }
    }

    private fun awaitKeyboard(visible: Boolean) {
        compose.waitUntil(5_000) {
            var actual = false
            compose.runOnUiThread {
                actual =
                    ViewCompat
                        .getRootWindowInsets(
                            activity.window.decorView,
                        )?.isVisible(WindowInsetsCompat.Type.ime()) ==
                    true
            }
            actual == visible
        }
    }
}

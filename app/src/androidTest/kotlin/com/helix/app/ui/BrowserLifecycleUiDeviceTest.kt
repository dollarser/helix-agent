package com.helix.app.ui

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserEngineState
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.storage.BrowserSessionStore
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserLifecycleUiDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: BrowserController
    private lateinit var context: Context
    private val visible = mutableStateOf(true)

    @Test
    fun nativeHomeWarmsEngineWithoutCreatingTabsOrViews() {
        launchBrowser()
        compose.waitUntil(15_000) { controller.engineState.value == BrowserEngineState.READY }
        compose.runOnIdle {
            assertTrue(
                controller.state.value.tabs
                    .isEmpty(),
            )
            assertNull(controller.state.value.selectedId)
            controller.warmUpEngine()
            assertEquals(BrowserEngineState.READY, controller.engineState.value)
        }
        compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
    }

    @Test
    fun deniedAddressFromHomeShowsErrorAndClosingTabReturnsToItsNeighbor() {
        launchBrowser()
        val regular = openPage()
        val blank = onMain { controller.newTab() }
        onMain { controller.navigate(blank, "file:///not-allowed") }
        compose.onNodeWithTag("browser-error-page").assertIsDisplayed()
        onMain { controller.showHome() }
        compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
        onMain { controller.select(blank) }
        compose.onNodeWithTag("browser-error-page").assertIsDisplayed()
        onMain { controller.closeTab(blank) }
        assertEquals(regular, controller.state.value.selectedId)
        assertFalse(controller.state.value.isHomeVisible)
        assertTrue(controller.hostView(regular) != null)
    }

    @Test
    fun leavingAndHomeKeepActualPageWhileExplicitCloseReleasesIt() {
        launchBrowser()
        val id = openPage()
        val view = onMain { controller.hostView(id)!! }
        evaluate(view, "document.getElementById('draft').value='unsent';'ok'")
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithText("other section").assertIsDisplayed()
        compose.runOnIdle { visible.value = true }
        assertSame(view, onMain { controller.hostView(id) })
        assertEquals("\"unsent\"", evaluate(view, "document.getElementById('draft').value"))
        compose.runOnIdle { controller.showHome() }
        compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
        assertEquals(PAGE, controller.tab(id)!!.url)
        compose.onNodeWithTag("browser-return-page").performClick()
        assertSame(view, onMain { controller.hostView(id) })
        assertEquals("\"unsent\"", evaluate(view, "document.getElementById('draft').value"))
        Espresso.pressBack()
        compose.waitUntil(5_000) { !visible.value }
        compose.onNodeWithText("other section").assertIsDisplayed()
        assertSame(view, onMain { controller.hostView(id) })
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("browser-menu").performClick()
        compose.onNodeWithTag("browser-close").performClick()
        compose.onNodeWithTag("browser-close-confirm").assertIsDisplayed()
        // The new dialog owns focus, so do not select Espresso's retiring sheet/Activity root.
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(
                "input keyevent KEYCODE_BACK",
            ).use {
                FileInputStream(it.fileDescriptor).readBytes()
            }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("browser-close-confirm").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("browser-close-confirm").assertDoesNotExist()
        assertSame(view, onMain { controller.hostView(id) })
        compose.onNodeWithTag("browser-menu").performClick()
        compose.onNodeWithTag("browser-close").performClick()
        compose.onNodeWithTag("browser-close-confirm").performClick()
        compose.waitUntil(5_000) { !visible.value }
        assertNull(onMain { controller.hostView(id) })
        assertTrue(controller.state.value.isClosed)
        assertEquals(PAGE, controller.tab(id)!!.url)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("browser-return-page").performClick()
        compose.onNodeWithTag("browser-reopen-page").assertIsDisplayed().performClick()
        awaitPage(id)
        val replacement = onMain { controller.hostView(id)!! }
        assertNotSame(view, replacement)
        assertEquals("\"\"", evaluate(replacement, "document.getElementById('draft').value"))
    }

    @Test
    fun closeKeepsSiteDataAndOnlyCheckpointsOrdinaryTabs() {
        launchBrowser()
        val regular = openPage()
        val privateTab = onMain { controller.newTab(isIncognito = true) }
        onMain { controller.navigate(privateTab, PRIVATE_PAGE) }
        awaitPage(privateTab)
        val task = onMain { controller.openTab(TASK_PAGE, "synthetic-origin").tabId }
        awaitPage(task)
        onMain { controller.addBookmark("fixture", PAGE) }
        compose.waitUntil(5_000) { controller.bookmarks.value.any { it.url == PAGE } }
        compose.waitUntil(5_000) { controller.history.value.any { it.url == TASK_PAGE } }
        assertFalse(controller.history.value.any { it.url == PRIVATE_PAGE })
        val history = controller.history.value
        val bookmarks = controller.bookmarks.value
        val cookieUrl = "https://browser-${UUID.randomUUID()}.invalid/"
        setCookie(cookieUrl, "fixture=retained; Secure")
        try {
            assertTrue(closeBrowser())
            assertEquals(
                listOf(regular),
                controller.state.value.tabs
                    .map { it.id },
            )
            assertNull(onMain { controller.hostView(regular) })
            assertEquals("browser-closed", onMain { controller.openTab(PAGE).failureReason })
            assertEquals(history, controller.history.value)
            assertEquals(bookmarks, controller.bookmarks.value)
            assertTrue(onMain { CookieManager.getInstance().getCookie(cookieUrl).contains("fixture=retained") })
            val saved = BrowserSessionStore(File(context.filesDir, "browser/session.json")).read()
            assertEquals(listOf(PAGE), saved.tabs.map { it.url })
            assertEquals("Lifecycle fixture", saved.tabs.single().title)
        } finally {
            setCookie(cookieUrl, "fixture=; Max-Age=0; Secure")
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun ordinaryBackgroundKeepsPageButCriticalTrimRetiresItWithoutReloading() {
        launchBrowser()
        val id = openPage()
        val view = onMain { controller.hostView(id)!! }
        val generation = controller.tab(id)!!.navigationGeneration
        onMain { controller.trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) }
        assertSame(view, onMain { controller.hostView(id) })
        onMain { controller.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
        assertNull(onMain { controller.hostView(id) })
        assertEquals(PAGE, controller.tab(id)!!.url)
        assertTrue(controller.tab(id)!!.navigationGeneration > generation)
        assertFalse(controller.tab(id)!!.isLoading)
        compose.onNodeWithTag("browser-reopen-page").assertIsDisplayed()
    }

    @Test
    fun failedCheckpointReportsFailureButStillReleasesPage() {
        launchBrowser()
        val id = openPage()
        assertTrue(runBlocking { withContext(Dispatchers.Main) { controller.flushSession() } })
        val file = File(context.filesDir, "browser/session.json")
        check(file.delete())
        check(file.mkdir())
        onMain { controller.setDesktopMode(id, true) }
        assertFalse(closeBrowser())
        assertTrue(file.isDirectory)
        assertNull(onMain { controller.hostView(id) })
        assertEquals(PAGE, controller.tab(id)!!.url)
    }

    private fun launchBrowser() {
        context = browserTestContext()
        onMain { controller = BrowserController(context) }
        runBlocking { controller.sessionReady.await() }
        compose.setContent {
            val activity = LocalContext.current as Activity
            DisposableEffect(Unit) {
                val owner = BrowserViewOwner(activity)
                controller.attach(owner)
                onDispose {
                    controller.detach(owner)
                    owner.destroy()
                }
            }
            MaterialTheme {
                if (visible.value) {
                    BrowserScreen(
                        controller,
                        onExitBrowser = { visible.value = false },
                    )
                } else {
                    Text("other section")
                }
            }
        }
        compose.waitUntil(15_000) { controller.engineState.value == BrowserEngineState.READY }
    }

    private fun openPage(): String = onMain { controller.openTab(PAGE).tabId }.also(::awaitPage)

    private fun awaitPage(id: String) {
        compose.waitUntil(10_000) { controller.tab(id)?.isLoading == false && controller.tab(id)?.title != null }
    }

    private fun closeBrowser(): Boolean = runBlocking { withContext(Dispatchers.Main) { controller.closeBrowser() } }

    private fun <T> onMain(action: () -> T): T {
        var result: T? = null
        compose.runOnUiThread { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun evaluate(
        view: WebView,
        script: String,
    ): String {
        val done = CountDownLatch(1)
        var result = ""
        onMain {
            view.evaluateJavascript(script) {
                result = it
                done.countDown()
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        return result
    }

    private fun setCookie(
        url: String,
        value: String,
    ) {
        val done = CountDownLatch(1)
        onMain { CookieManager.getInstance().setCookie(url, value) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    companion object {
        private const val PAGE = "data:text/html,<title>Lifecycle fixture</title><input id='draft'/>"
        private const val PRIVATE_PAGE = "data:text/html,<title>Private fixture</title>"
        private const val TASK_PAGE = "data:text/html,<title>Task fixture</title>"
    }
}

package com.helix.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserViewOwner
import com.helix.feature.browser.ui.BrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Host runs seed, terminates the app process, then runs recover with the same fixture UUID. */
class BrowserSessionRestartDeviceTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var controller: BrowserController

    @Test
    fun normalMetadataSurvivesProcessDeathWithoutPageOrAuthorityReplay() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("browserPhase")
        org.junit.Assume.assumeTrue("Requires explicit process restart orchestration", phase != null)
        require(phase in setOf("seed", "recover", "recover-closed"))
        val fixture = UUID.fromString(args.getString("browserFixture")).toString()
        val base = ApplicationProvider.getApplicationContext<Context>()
        val files = File(base.cacheDir, "browser-restart-$fixture").apply { mkdirs() }
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getFilesDir(): File = files
            }
        compose.runOnUiThread { controller = BrowserController(context) }
        runBlocking { controller.sessionReady.await() }
        if (phase == "recover-closed") {
            assertTrue(controller.state.value.isClosed)
            compose.runOnUiThread {
                assertEquals("browser-closed", controller.openTab(PAGE).failureReason)
            }
        }
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
            MaterialTheme { BrowserScreen(controller) }
        }
        when (phase) {
            "seed" -> {
                seed(files)
            }

            "recover" -> {
                recover(files)
            }

            "recover-closed" -> {
                compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
                compose.runOnIdle {
                    assertFalse(controller.state.value.isClosed)
                    val tab =
                        controller.state.value.tabs
                            .single()
                    assertEquals(PAGE, tab.url)
                    assertEquals("Normal restart", tab.title)
                    assertNull(controller.hostView(tab.id))
                }
            }
        }
    }

    private fun seed(files: File) {
        lateinit var regular: String
        compose.runOnUiThread {
            regular = controller.openTab(PAGE).tabId
            val privateTab = controller.newTab(isIncognito = true)
            controller.navigate(privateTab, PRIVATE_PAGE)
            controller.openTab(TASK_PAGE, "restart-task-origin")
            controller.select(regular)
        }
        compose.waitUntil(10_000) {
            controller.state.value.tabs
                .all { !it.isLoading && it.title != null }
        }
        compose.runOnUiThread {
            controller.hostView(regular)!!.evaluateJavascript("document.querySelector('input').value='unsent'", null)
        }
        assertTrue(runBlocking { withContext(Dispatchers.Main) { controller.flushSession() } })
        File(files, "identity.txt").writeText("${Process.myPid()}\n$regular")
        val saved = File(files, "browser/session.json").readText()
        assertFalse(saved.contains("restart-task-origin"))
        assertFalse(saved.contains("private-restart"))
        assertFalse(saved.contains("unsent"))
    }

    private fun recover(files: File) {
        val old = File(files, "identity.txt").readLines()
        assertNotEquals(old[0].toInt(), Process.myPid())
        val restored =
            controller.state.value.tabs
                .single()
        assertNotEquals(old[1], restored.id)
        assertEquals(PAGE, restored.url)
        assertEquals("Normal restart", restored.title)
        assertNull(restored.originSessionId)
        assertFalse(restored.isIncognito)
        assertFalse(restored.canGoBack)
        compose.runOnIdle { assertNull(controller.hostView(restored.id)) }
        compose.onNodeWithTag("browser-home-search").assertIsDisplayed()
        compose.onNodeWithTag("browser-return-page").performClick()
        compose.onNodeWithTag("browser-reopen-page").assertIsDisplayed()
        compose.runOnIdle { assertNull(controller.hostView(restored.id)) }
        compose.onNodeWithTag("browser-reopen-page").performClick()
        compose.waitUntil(10_000) { !controller.tab(restored.id)!!.isLoading }
        assertTrue(runBlocking { withContext(Dispatchers.Main) { controller.closeBrowser() } })
        compose.runOnIdle { assertNull(controller.hostView(restored.id)) }
    }

    companion object {
        private const val PAGE = "data:text/html,<title>Normal restart</title><input/>"
        private const val PRIVATE_PAGE = "data:text/html,<title>private-restart</title>"
        private const val TASK_PAGE = "data:text/html,<title>task-restart</title>"
    }
}

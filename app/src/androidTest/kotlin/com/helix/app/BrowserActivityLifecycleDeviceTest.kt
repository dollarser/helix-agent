package com.helix.app

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserNavResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserActivityLifecycleDeviceTest {
    @Test fun taskPageKeepsManualSelectionAndRendersItsOwnPixelsWithoutActionReplay() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var controller: BrowserController
        lateinit var task: String
        lateinit var manual: String
        val created = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            controller = readyController(scenario)
            try {
                scenario.onActivity {
                    manual = controller.openTab("data:text/html,<body>USER_PAGE_UNCHANGED</body>").tabId
                    created += manual
                    task = controller.openTab(TASK_PAGE, "synthetic-browser-origin").tabId
                    created += task
                    assertEquals(manual, controller.state.value.selectedId)
                }
                awaitTaskPixels(controller, task)
                scenario.recreate()
                readyController(scenario)
                scenario.onActivity {
                    assertEquals(manual, controller.state.value.selectedId)
                    assertNull(controller.hostView(task))
                    assertEquals("synthetic-browser-origin", controller.tab(task)!!.originSessionId)
                    assertTrue(controller.navigateOutcome(task, TASK_PAGE) is BrowserNavResult.Started)
                    assertEquals(manual, controller.state.value.selectedId)
                }
            } finally {
                instrumentation.runOnMainSync {
                    closeFixtureTabs(controller, created)
                }
            }
        }
    }

    @Test fun pageCallbacksRemainBoundAcrossHomeAndScreenOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var id: String? = null
        lateinit var controller: BrowserController
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            controller = readyController(scenario)
            try {
                scenario.onActivity {
                    val opened = controller.openTab(PAGE)
                    assertTrue(opened.failureReason, opened.tabId.isNotBlank())
                    id = opened.tabId
                }
                for (key in listOf("KEYCODE_HOME", "KEYCODE_SLEEP")) {
                    instrumentation.uiAutomation.executeShellCommand("input keyevent $key").close()
                    android.os.SystemClock.sleep(500)
                    val callback = CountDownLatch(1)
                    instrumentation.runOnMainSync {
                        controller.hostView(requireNotNull(id))!!.evaluateJavascript("1+1") {
                            assertEquals("2", it)
                            callback.countDown()
                        }
                    }
                    assertTrue(
                        "Background JS callback must finish within its tool deadline",
                        callback.await(7, TimeUnit.SECONDS),
                    )
                }
            } finally {
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation
                    .executeShellCommand(
                        "am start -W -n ${instrumentation.targetContext.packageName}/com.helix.app.MainActivity",
                    ).close()
                instrumentation.runOnMainSync { id?.let { closeFixtureTabs(controller, listOf(it)) } }
            }
        }
    }

    @Test
    fun repeatedNewTabAtCapacityDoesNotCrashAndCanRecoverAfterClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controller = readyController(scenario)
            scenario.onActivity {
                controller.state.value.tabs
                    .toList()
                    .forEach { controller.closeTab(it.id) }
                repeat(8) { assertTrue(controller.tryNewTab() != null) }
                repeat(3) { assertNull(controller.tryNewTab()) }
                assertEquals(8, controller.state.value.tabs.size)
                controller.closeTab(
                    controller.state.value.tabs
                        .first()
                        .id,
                )
                assertTrue(controller.tryNewTab() != null)
                controller.state.value.tabs
                    .toList()
                    .forEach { controller.closeTab(it.id) }
            }
        }
    }

    @Test
    fun mainActivityRecreationPreservesOnlyLogicalTabsUntilExplicitNavigation() {
        lateinit var controller: BrowserController
        lateinit var firstView: WebView
        lateinit var id: String
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            controller = readyController(scenario)
            scenario.onActivity { activity ->
                id = controller.openTab(PAGE).tabId
                firstView = controller.hostView(id)!!
                assertSame(activity, firstView.context)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertSame(firstView, controller.hostView(id)) }
            repeat(3) {
                scenario.recreate()
                readyController(scenario)
                scenario.onActivity { activity ->
                    assertSame(controller, (activity.application as HelixApplication).appContainer.browser)
                    assertEquals(PAGE, controller.tab(id)!!.url)
                    assertNull(controller.hostView(id))
                    assertTrue(controller.navigateOutcome(id, PAGE) is BrowserNavResult.Started)
                    val replacement = controller.hostView(id)!!
                    assertNotSame(firstView, replacement)
                    assertSame(activity, replacement.context)
                }
            }
            scenario.onActivity { controller.closeTab(id) }
        }
    }

    private fun readyController(scenario: ActivityScenario<MainActivity>): BrowserController {
        lateinit var controller: BrowserController
        scenario.onActivity { controller = (it.application as HelixApplication).appContainer.browser }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { controller.sessionReady.await() } }
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        var available = false
        while (!available && android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { available = controller.hostAvailable() }
            if (!available) Thread.sleep(10)
        }
        assertTrue("MainActivity must attach its browser owner before page commands", available)
        return controller
    }

    private fun closeFixtureTabs(
        controller: BrowserController,
        created: List<String>,
    ) {
        created.reversed().forEach { id -> if (controller.tab(id) != null) controller.closeTab(id) }
    }

    private fun awaitTaskPixels(
        controller: BrowserController,
        task: String,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
        var painted = false
        while (!painted && android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                painted = hasTaskPixels(controller.capturePagePng(task))
            }
            if (!painted) Thread.sleep(100)
        }
        assertTrue("Unselected task must render its own actual page, not a blank or manual page", painted)
    }

    private fun hasTaskPixels(bytes: ByteArray?): Boolean {
        if (bytes == null) return false
        val image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return try {
            image.getPixel(image.width / 2, image.height / 2) == android.graphics.Color.rgb(19, 168, 156)
        } finally {
            image.recycle()
        }
    }

    companion object {
        private const val TASK_PAGE =
            "data:text/html,<html><body style='margin:0;background:rgb(19,168,156);height:100vh'>" +
                "TASK_PAGE</body></html>"
        private const val PAGE = "data:text/html,<html><body>Activity recreation fixture</body></html>"
    }
}

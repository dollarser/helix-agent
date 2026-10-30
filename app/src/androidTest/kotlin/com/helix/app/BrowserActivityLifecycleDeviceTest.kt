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
    @Test fun pageCallbacksRemainBoundAcrossHomeAndScreenOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var id: String? = null
        lateinit var controller: BrowserController
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    controller = (activity.application as HelixApplication).appContainer.browser
                    id = controller.openTab(PAGE).tabId
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
                instrumentation.runOnMainSync { id?.let(controller::closeTab) }
            }
        }
    }

    @Test
    fun repeatedNewTabAtCapacityDoesNotCrashAndCanRecoverAfterClose() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = (activity.application as HelixApplication).appContainer.browser
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
            scenario.onActivity { activity ->
                controller = (activity.application as HelixApplication).appContainer.browser
                id = controller.openTab(PAGE).tabId
                firstView = controller.hostView(id)!!
                assertSame(activity, firstView.context)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { assertSame(firstView, controller.hostView(id)) }
            repeat(3) {
                scenario.recreate()
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

    companion object {
        private const val PAGE = "data:text/html,<html><body>Activity recreation fixture</body></html>"
    }
}

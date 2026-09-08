package com.helix.app

import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.feature.browser.BrowserController
import com.helix.feature.browser.BrowserNavResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserActivityLifecycleDeviceTest {
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

package com.helix.feature.browser

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.browser.storage.BrowserPreferences
import com.helix.feature.browser.storage.BrowserStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserRedesignDeviceTest {
    @Test
    fun normalAndNoHistoryTabsRespectCapacityWithoutThrowing() {
        BrowserActivityFixture().use { fixture ->
            fixture.scenario.onActivity {
                repeat(BrowserTabController.DEFAULT_MAX_TABS) { index ->
                    assertTrue(fixture.controller.tryNewTab(index % 2 == 0) != null)
                }
                assertNull(fixture.controller.tryNewTab(false))
                assertNull(fixture.controller.tryNewTab(true))
                assertEquals(BrowserTabController.DEFAULT_MAX_TABS, fixture.controller.state.value.tabs.size)
            }
        }
    }

    @Test
    fun imageAndDesktopSettingsApplyToLazilyCreatedAndReplacementHosts() {
        BrowserStorage(InstrumentationRegistry.getInstrumentation().targetContext)
            .savePreferences(BrowserPreferences(noImageMode = true))
        BrowserActivityFixture().use { fixture ->
            await { fixture.controller.preferences.value.noImageMode }
            fixture.scenario.onActivity { activity ->
                val controller = fixture.controller
                val id = controller.newTab()
                controller.setDesktopMode(id, true)
                controller.navigate(id, BrowserTabController.ABOUT_BLANK)
                val first = requireNotNull(controller.hostView(id))
                assertTrue(first.settings.blockNetworkImage)
                assertTrue(first.settings.userAgentString.contains("X11"))
                controller.detach(activity.owner)
                activity.owner = BrowserViewOwner(activity)
                controller.attach(activity.owner)
                controller.resume(activity.owner)
                controller.navigate(id, BrowserTabController.ABOUT_BLANK)
                val second = requireNotNull(controller.hostView(id))
                assertTrue(second.settings.blockNetworkImage)
                assertTrue(second.settings.userAgentString.contains("X11"))
            }
        }
    }

    @Test
    fun oversizedSaveReportsFailureAndPreservesPublishedBookmarks() {
        BrowserActivityFixture().use { fixture ->
            val controller = fixture.controller
            fixture.scenario.onActivity { controller.addBookmark("kept", "https://kept.test/") }
            await { controller.bookmarks.value.any { it.url == "https://kept.test/" } }
            val before = controller.bookmarks.value
            fixture.scenario.onActivity { controller.addBookmark("oversized", "x".repeat(2 * 1024 * 1024 + 1)) }
            await { controller.storageFailure.value }
            assertEquals(before, controller.bookmarks.value)
            val stored = BrowserStorage(InstrumentationRegistry.getInstrumentation().targetContext).loadBookmarks()
            assertEquals(before, stored)
            assertFalse(stored.any { it.title == "oversized" })
        }
    }

    private fun await(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
        assertTrue(predicate())
    }
}

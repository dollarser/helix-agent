package com.helix.smoke

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The public plugin UI, real durable selection and a separately enabled test-only backend. */
@RunWith(AndroidJUnit4::class)
class MobileUseReleaseTest {
    private val ui = ReleaseUiTestSupport()

    @Test fun configuredScopeAndConversationChoiceSurviveRestartUntilUserRevokes() {
        check(ui.context.packageName.endsWith(".developer"))
        assertEquals(0, ui.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        ui.ready()
        openMobileUse()
        ui.scrollTo("插件设置", "Plugin settings")
        ui.activate("插件设置", "Plugin settings")
        ui.scrollTo("选择应用（已选 0 个）", "Choose apps (0 selected)")
        val wholePhone = ui.nodes().single { it.isCheckable }
        assertTrue("The initial draft uses the whole-phone option", wholePhone.isChecked)
        ui.toggleChoice()
        awaitChoice(false)
        ui.activate("选择应用（已选 0 个）", "Choose apps (0 selected)")
        ui.enterText(ui.context.packageName)
        ui.activate(ui.context.packageName)
        assertTrue(ui.checkedChoice(ui.context.packageName))
        ui.activate("确认选择", "Confirm selection")
        ui.await("选择应用（已选 1 个）", "Choose apps (1 selected)")
        ui.activate("保存插件设置", "Save plugin settings")
        ui.await("已保存，对启用此插件的会话生效", "Saved for conversations using this plugin")
        ui.back()
        openCard()
        openChoice()
        assertFalse("Saving configuration must not select a conversation", choiceChecked())
        ui.toggleChoice()
        awaitChoice(true)
        ui.back()
        ui.restartTarget()
        openMobileUse()
        openChoice()
        awaitChoice(true)
        ui.toggleChoice()
        awaitChoice(false)
        ui.back()
        ui.restartTarget()
        openMobileUse()
        openChoice()
        assertFalse("An explicit deselection must remain revoked after restart", choiceChecked())
        ui.back()
        ui.scrollTo("插件设置", "Plugin settings")
        ui.activate("插件设置", "Plugin settings")
        ui.scrollTo("选择应用（已选 1 个）", "Choose apps (1 selected)")
        ui.activate("选择应用（已选 1 个）", "Choose apps (1 selected)")
        ui.enterText(ui.context.packageName)
        assertTrue("The configured app scope must survive process death", ui.checkedChoice(ui.context.packageName))
    }

    private fun openMobileUse() {
        ui.click("应用导航", "App navigation")
        ui.click("扩展", "Extensions")
        ui.enterText("mobile-use")
        openCard()
    }

    private fun openCard() {
        if (ui.node("插件设置", "Plugin settings") == null) ui.activate("Mobile Use")
        ui.await("插件设置", "Plugin settings")
    }

    private fun openChoice() {
        ui.scrollTo("在会话中使用", "Use in a conversation")
        ui.activate("在会话中使用", "Use in a conversation")
        ui.await("mobile-use")
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (ui.nodes().count { it.isCheckable } != 1 &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        assertEquals(
            "The filtered plugin panel must contain exactly one choice",
            1,
            ui.nodes().count { it.isCheckable },
        )
    }

    private fun choiceChecked() = ui.nodes().single { it.isCheckable }.isChecked

    private fun awaitChoice(expected: Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (ui.nodes().singleOrNull { it.isCheckable }?.isChecked != expected &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        assertEquals(
            "The durable conversation selection did not update; " +
                ui.nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "),
            expected,
            ui.nodes().singleOrNull { it.isCheckable }?.isChecked,
        )
    }
}

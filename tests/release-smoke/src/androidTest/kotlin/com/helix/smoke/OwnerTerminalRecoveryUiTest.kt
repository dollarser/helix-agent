package com.helix.smoke

import android.app.UiAutomation
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit daily-device recovery checks. Never clears app data, sends chat or replays a command. */
class OwnerTerminalRecoveryUiTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val automation =
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    @Test fun replacedTerminalOffersCloseWithoutFalseReconnectOrObserver() {
        require(arguments.getString("ownerTerminalRecovery") == "inspect-replaced")
        await("关闭终端")
        val labels = nodes().mapNotNull { it.text?.toString() }
        assertTrue(labels.any { "原命令结果未知" in it })
        assertFalse(labels.any { "重新连接" == it || "只读观察模式" in it })
        assertFalse(labels.any { "操作未完成" in it })
    }

    @Test fun explicitlyCloseTwoReplacedTerminalsAndStartNew() {
        require(arguments.getString("ownerTerminalRecovery") == "close-two-and-start")
        repeat(2) {
            click("关闭终端")
            SystemClock.sleep(500)
        }
        click("启动终端")
        await("RUNNING")
        assertFalse(nodes().any { it.text?.toString()?.contains("操作未完成") == true })
    }

    @Test fun liveTerminalHasWritableConnectionAfterReentry() {
        require(arguments.getString("ownerTerminalRecovery") == "inspect-live")
        await("RUNNING")
        assertTrue(await("显示键盘").isEnabled)
        assertFalse(nodes().any { it.text?.toString()?.contains("只读观察模式") == true })
    }

    @Test fun closeCurrentTerminalIsOneAction() {
        require(arguments.getString("ownerTerminalRecovery") == "close-current")
        click("关闭终端")
        SystemClock.sleep(1500)
        val labels = nodes().mapNotNull { it.text?.toString() }
        assertFalse(labels.any { "操作未完成" in it || "关闭未完成" in it })
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        require(arguments.getString("targetPackage") == "com.helix.agent.developer")
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()

        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        return automation.rootInActiveWindow
            ?.let(::flatten)
            .orEmpty()
            .filter { it.packageName?.toString() == "com.helix.agent.developer" && it.isVisibleToUser }
    }

    private fun await(label: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            nodes()
                .firstOrNull {
                    it.text?.toString() == label || it.contentDescription?.toString() == label
                }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Terminal control unavailable: $label")
    }

    private fun click(label: String) {
        val node = await(label)
        val clickable = generateSequence(node) { it.parent }.first { it.isClickable }
        check(clickable.isEnabled && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}

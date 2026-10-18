package com.helix.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Owner-authorized daily-device checks; never creates tasks, edits preferences or clears data. */
class OwnerTerminalLayoutUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val limit get() = (arguments.getString("terminalLimit") ?: "4").toInt().also { require(it in 1..4) }
    private val count get() =
        checkNotNull(
            arguments.getString("terminalCount"),
        ).toInt().also { require(it in 1..limit) }
    private val density get() = instrumentation.context.resources.displayMetrics.density

    init {
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
    }

    @Test fun toolbarUsesOneRowWithFullTouchTargetsAndRoomForTheTerminal() {
        awaitKeyboard()
        val controls = listOf("离开终端，保留运行", "显示键盘", "关闭终端", "终端选项")
        val controlBounds = controls.map { bounds(clickable(await(it))) }
        val center = controlBounds.first().centerY()
        controlBounds.forEach {
            assertTrue("Toolbar action wrapped: $it", abs(it.centerY() - center) <= 2)
            assertTrue("Small touch target: $it", it.width() >= 48 * density - 2 && it.height() >= 48 * density - 2)
        }
        assertTrue(
            "Toolbar is too tall",
            controlBounds.maxOf { it.bottom } - controlBounds.minOf { it.top } <= 80 * density,
        )
        assertFalse(nodes().any { label(it).startsWith("起始目录：") || label(it) == "RUNNING" })
        val keys = bounds(clickable(await("Esc")))
        assertTrue("Terminal viewport has no room", keys.top - controlBounds.maxOf { it.bottom } >= 80 * density)
    }

    @Test fun terminalInfoAndHelpAreAvailableWithoutPermanentMetadataRows() {
        awaitKeyboard()
        click("终端选项")
        click("终端信息")
        await("正在运行")
        assertTrue(nodes().any { label(it).startsWith("起始目录：") })
        assertTrue(nodes().any { label(it).contains("pwd") && label(it).contains("cd") })
        click("关闭")
        waitUntil { nodes().none { label(it).startsWith("起始目录：") } }
        assertFalse(nodes().any { label(it).startsWith("起始目录：") })
        click("终端选项")
        click("帮助")
        await("帮助")
        click("关闭")
        awaitKeyboard()
    }

    @Test fun terminalPickerSwitchesOnlyExistingTerminalsAndShowsNewWhenCapacityAllows() {
        awaitKeyboard()
        val original = (1..count).single { index -> nodes().any { label(it) == "终端 $index" } }
        click("终端 $original")
        (1..count).forEach { await("终端 $it") }
        if (count < limit) await("新建终端")
        assertEquals(count < limit, nodes().any { label(it) == "新建终端" })
        click("终端 1")
        awaitTerminal(1)
        for (index in 2..count) {
            click("终端 ${index - 1}")
            click("终端 $index")
            awaitTerminal(index)
        }
        val current = count
        if (original != current) {
            click("终端 $current")
            click("终端 $original")
            awaitTerminal(original)
        }
        assertTrue(nodes().any { label(it) == "终端 $original" })
    }

    @Test fun toolbarCanShowAndHideTheActualKeyboard() {
        awaitKeyboard()
        click("显示键盘")
        await("收起键盘")
        waitUntil {
            automation.windows.any {
                it.type ==
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        }
        click("收起键盘")
        await("显示键盘")
        waitUntil {
            automation.windows.none {
                it.type ==
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
        }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        require(arguments.getString("ownerTerminalLayout") == "inspect")
        require(arguments.getString("targetPackage") == "com.helix.agent.developer")
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()

        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        return automation.rootInActiveWindow
            ?.let(::flatten)
            .orEmpty()
            .filter { it.packageName?.toString() == "com.helix.agent.developer" && it.isVisibleToUser }
    }

    private fun label(node: AccessibilityNodeInfo): String =
        node.text?.toString() ?: node.contentDescription?.toString().orEmpty()

    private fun bounds(node: AccessibilityNodeInfo): Rect = Rect().also { node.getBoundsInScreen(it) }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        generateSequence(node) { it.parent }.first { it.isClickable }

    private fun await(expectedLabel: String): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitUntil { nodes().firstOrNull { label(it) == expectedLabel }?.also { result = it } != null }
        return checkNotNull(result)
    }

    private fun awaitKeyboard() {
        waitUntil { nodes().firstOrNull { label(it) == "显示键盘" }?.let { clickable(it).isEnabled } == true }
    }

    private fun awaitTerminal(index: Int) {
        waitUntil {
            val visible = nodes().map(::label)
            "终端 $index" in visible && "新建终端" !in visible &&
                (1..count).filter { it != index }.none { "终端 $it" in visible }
        }
        awaitKeyboard()
    }

    private fun click(label: String) {
        val target = clickable(await(label))
        check(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Terminal UI condition timed out")
    }
}

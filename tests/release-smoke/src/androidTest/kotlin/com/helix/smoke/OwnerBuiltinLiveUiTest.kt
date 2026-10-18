package com.helix.smoke

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit owner-operated input only; never sends, changes an IME or accesses app-private state. */
@RunWith(AndroidJUnit4::class)
class OwnerBuiltinLiveUiTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val target = requireNotNull(arguments.getString("targetPackage"))
    private val automation =
        InstrumentationRegistry
            .getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun nodes(): List<AccessibilityNodeInfo> {
        require(arguments.getString("ownerBuiltinLive") == "synthetic-draft-only")
        require(target in setOf("com.helix.agent", "com.helix.agent.developer"))
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()

        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { node.getChild(it)?.let(::flatten).orEmpty() }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var candidate = automation.rootInActiveWindow
        while (candidate == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            candidate = automation.rootInActiveWindow
        }
        val root = requireNotNull(candidate)
        check(root.packageName.toString() == target)
        return flatten(root).filter { it.packageName.toString() == target && it.isVisibleToUser }
    }

    @Test fun enterSyntheticPromptWithoutChangingIme() {
        val prompt = requireNotNull(arguments.getString("syntheticPrompt"))
        require(prompt.startsWith("HELIX_BUILTIN_LIVE_") && prompt.length <= 1500)
        val field = nodes().single { it.isEditable }
        val previous = field.text?.toString().orEmpty()
        check(previous.isBlank() || previous.startsWith("HELIX BUILT IN LIVE") || previous == "1+1") {
            "Refusing to replace an unowned composer draft"
        }
        check(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                },
            ),
        )
        SystemClock.sleep(300)
        assertEquals(prompt, nodes().single { it.isEditable }.text.toString())
    }

    @Test fun conversationDoesNotShowBuiltinTransmissionReminder() {
        val visible = nodes().mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
        check(visible.count { it == "新建会话" || it == "New conversation" } >= 2) {
            "Reminder regression must run on an empty new-conversation draft"
        }
        check(visible.any { it == "Qwen3.8-27B ▾" })
        assertFalse(visible.any { "发送至项目云端服务" in it || "sends messages, context" in it })
    }
}

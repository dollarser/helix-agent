package com.helix.app.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Actual installed IME key processing and candidate selection; no UTF-8 injection or model call. */
class PinyinComposerDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinyinCompositionAndChineseCommitReachDraftWithoutSending() {
        val probe = mutableStateOf(TextFieldValue())
        val draft = mutableStateOf("")
        var sends = 0
        compose.setContent {
            MaterialTheme {
                Column(Modifier.safeDrawingPadding().imePadding()) {
                    BasicTextField(
                        probe.value,
                        { probe.value = it },
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("ime-probe"),
                    )
                    ConversationComposer(
                        draft.value,
                        { draft.value = it },
                        false,
                        false,
                        ComposerActions(onFile = {}, onVoice = {}, onSend = { sends++ }, onStop = {}),
                    )
                }
            }
        }
        compose.onNodeWithTag("ime-probe").performClick().assertIsFocused()
        typePinyin()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString("stream", "PINYIN_PROBE text=${probe.value.text} composition=${probe.value.composition}\n")
            },
        )
        // Gboard keeps Pinyin preedit in its own window; other IMEs may also expose TextField composition.
        // Capture the IME preedit and verify its Chinese candidate and actual commit, regardless of its IPC strategy.
        commitCandidate()
        compose.waitUntil(10_000) { probe.value.text == "你好" && probe.value.composition == null }
        compose.onNodeWithTag("ime-probe").assertTextEquals("你好")
        compose.onNodeWithTag("chat-input").performClick().assertIsFocused()
        typePinyin()
        commitCandidate()
        compose.waitUntil(10_000) { draft.value == "你好" }
        compose.onNodeWithTag("chat-input").assertTextEquals("你好")
        compose.runOnIdle { assertEquals(0, sends) }
    }

    private fun typePinyin() {
        // Google Pinyin's first-use layout chooser is an IME setup screen, not a text key.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val layout =
            automation.windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .firstNotNullOfOrNull { window -> window.root?.let { findCandidate(it, "Pinyin keyboard") } }
        if (layout != null) clickKeyboard("Pinyin keyboard")
        "nihao".forEach { clickKeyboard(it.toString()) }
    }

    private fun capturePreedit(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStatus(
            0,
            android.os.Bundle().apply {
                val labels =
                    instrumentation.uiAutomation.windows
                        .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                        .flatMap { window -> window.root?.let(::keyboardLabels).orEmpty() }
                        .take(100)
                putString("stream", "PINYIN_IME_NODES=$labels\n")
            },
        )
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            instrumentation.targetContext.cacheDir.resolve(name).outputStream().use { stream ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            }
            bitmap.recycle()
        }
    }

    private fun keyboardLabels(node: AccessibilityNodeInfo): List<String> =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()) +
            (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::keyboardLabels).orEmpty() }

    private fun commitCandidate() {
        // Predictions may rank a longer phrase first; committing its prefix is not the same Chinese draft.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            compose.waitUntil(2_000) { findKeyboardNode(automation, "你好") != null }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            if (findKeyboardNode(automation, "其他候选键") == null) throw failure
            clickKeyboard("其他候选键")
        }
        clickKeyboard("你好")
    }

    private fun findKeyboardNode(
        automation: android.app.UiAutomation,
        label: String,
    ): AccessibilityNodeInfo? =
        automation.windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            .firstNotNullOfOrNull { window -> window.root?.let { findCandidate(it, label) } }

    private fun clickKeyboard(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo =
            automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        var candidate: AccessibilityNodeInfo? = null
        var lastBounds = Rect()
        var stableSince = SystemClock.uptimeMillis()
        try {
            compose.waitUntil(10_000) {
                candidate = findKeyboardNode(automation, label)
                val currentBounds = Rect()
                candidate?.getBoundsInScreen(currentBounds)
                if (currentBounds != lastBounds) {
                    lastBounds = currentBounds
                    stableSince = SystemClock.uptimeMillis()
                }
                candidate != null && !currentBounds.isEmpty && SystemClock.uptimeMillis() - stableSince >= 250
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            capturePreedit("pinyin-failure.png")
            throw failure
        }
        if (label == "你好") capturePreedit("pinyin-preedit.png")
        val bounds = Rect()
        requireNotNull(candidate).getBoundsInScreen(bounds)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply {
                putString(
                    "stream",
                    "PINYIN_TOUCH label=$label text=${candidate?.text} " +
                        "description=${candidate?.contentDescription} bounds=$bounds\n",
                )
            },
        )
        check(bounds.width() > 0 && bounds.height() > 0)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent
                    .obtain(
                        downTime,
                        SystemClock.uptimeMillis(),
                        action,
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        0,
                    ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                check(automation.injectInputEvent(event, true))
                if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(80)
            } finally {
                event.recycle()
            }
        }
        // Touch injection acknowledgement precedes the IME's asynchronous preedit/candidate update.
        SystemClock.sleep(150)
    }

    private fun findCandidate(
        node: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val candidateDescription =
            label == "你好" && description.substringBefore(',').substringBefore('，').trim() == label
        // A predicted Latin word (such as "I") is not the keyboard's lowercase letter key.
        if (text == label || description == label || candidateDescription) {
            return node
        }
        return (0 until node.childCount).firstNotNullOfOrNull { index ->
            node.getChild(index)?.let { findCandidate(it, label) }
        }
    }
}

package com.helix.smoke

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry

/** Only public UI and framework APIs: never import or reflect into the shrunk app. */
internal class ReleaseUiTestSupport {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target =
        requireNotNull(InstrumentationRegistry.getArguments().getString("targetPackage"))
            .also { require(it.matches(Regex("com\\.helix\\.validation\\.[a-z][a-z0-9]*(\\.developer)?"))) }
    val context = instrumentation.context.createPackageContext(target, 0)
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    fun launch() {
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        val component = requireNotNull(intent.component).flattenToString()
        require(component.matches(Regex("[A-Za-z0-9._]+/[A-Za-z0-9._]+")))
        val output =
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    automation.executeShellCommand("am start -W -n $component -f 0x10008000"),
                ).use { it.readBytes().toString(Charsets.UTF_8) }
        check("Status: ok" in output) { "Owned Activity launch did not complete: $output" }
    }

    fun node(vararg labels: String): AccessibilityNodeInfo? =
        nodes().firstOrNull {
            !it.isEditable && (it.text?.toString() in labels || it.contentDescription?.toString() in labels)
        } ?: automation.rootInActiveWindow?.let { find(it, labels.toSet()) }

    fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()

        fun flatten(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            listOf(node) + (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::flatten).orEmpty() }
        return automation.rootInActiveWindow
            ?.let(::flatten)
            .orEmpty()
            .filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
    }

    fun enterText(
        value: String,
        focus: Boolean = false,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (nodes().none { it.isEditable } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        var field =
            nodes().singleOrNull { it.isEditable } ?: error(
                "Expected one editable field; " +
                    nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "),
            )
        if (focus) {
            tap(field)
            field = nodes().single { it.isEditable && it.isFocused }
            check(field.packageName.toString() == context.packageName)
            require(value.matches(Regex("[A-Za-z0-9=_ .-]+"))) { "Keyboard fixture accepts bounded ASCII only" }
            ParcelFileDescriptor
                .AutoCloseInputStream(
                    automation.executeShellCommand("input text ${value.replace(" ", "%s")}"),
                ).use { it.readBytes() }
            SystemClock.sleep(400)
            if (Build.VERSION.SDK_INT >= 33) check(automation.clearCache())
            return
        }
        check(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        )
        SystemClock.sleep(400)
    }

    fun editField(
        value: String,
        vararg labels: String,
    ) {
        fun editableChildren(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
            if (node.isEditable) {
                listOf(node)
            } else {
                (0 until node.childCount)
                    .flatMap { node.getChild(it)?.let(::editableChildren).orEmpty() }
            }
        val labelled = await(*labels)
        val field =
            generateSequence(labelled) { it.parent }
                .mapNotNull { editableChildren(it).singleOrNull() }
                .firstOrNull()
                ?: error("Editable ancestor missing for ${labels.joinToString()}")
        check(
            field.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                },
            ),
        )
        SystemClock.sleep(300)
    }

    fun resumeTarget() {
        context.startActivity(
            requireNotNull(context.packageManager.getLaunchIntentForPackage(target))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        await("应用导航", "App navigation")
    }

    fun scrollTo(vararg labels: String) {
        repeat(8) {
            node(*labels)?.let { targetNode ->
                if (targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
                    SystemClock.sleep(400)
                }
                if (node(*labels)?.isVisibleToUser == true) return
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var scroll = nodes().firstOrNull { it.isScrollable && !it.isEditable }
            while (scroll == null && SystemClock.elapsedRealtime() < deadline) {
                if (node(*labels)?.isVisibleToUser == true) return
                SystemClock.sleep(100)
                scroll = nodes().firstOrNull { it.isScrollable && !it.isEditable }
            }
            checkNotNull(scroll) {
                "No owned scroll container; root=${automation.rootInActiveWindow?.packageName}; " +
                    nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")
            }
            if (!scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                check(node(*labels)?.isVisibleToUser == true) {
                    "Scroll did not reveal ${labels.joinToString()}; " +
                        nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")
                }
            }
            SystemClock.sleep(300)
        }
        check(node(*labels)?.isVisibleToUser == true) { "Could not scroll to ${labels.joinToString()}" }
    }

    fun restartTarget() {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("am force-stop $target")).use {
            it.readBytes()
        }
        SystemClock.sleep(1_500)
        if (Build.VERSION.SDK_INT >= 33) check(automation.clearCache())
        ready()
    }

    fun reconnectOwnedAccessibility() {
        fun command(value: String) =
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(value)).use {
                it.readBytes().toString(Charsets.UTF_8).trim()
            }
        val service = "$target/com.helix.extensions.mobileuse.automation.HelixAccessibilityService"
        val current = command("settings get secure enabled_accessibility_services")
        val others = current.split(':').filter { it.isNotBlank() && it != "null" && it != service }
        val removed = others.joinToString(":").ifEmpty { "null" }
        command("settings put secure enabled_accessibility_services $removed")
        SystemClock.sleep(300)
        val restored = (others + service).joinToString(":")
        command("settings put secure enabled_accessibility_services $restored")
        command("settings put secure accessibility_enabled 1")
        SystemClock.sleep(1_000)
    }

    private fun find(
        root: AccessibilityNodeInfo,
        labels: Set<String>,
    ): AccessibilityNodeInfo? {
        val matchesLabel = root.text?.toString() in labels || root.contentDescription?.toString() in labels
        if (root.packageName?.toString() == context.packageName && !root.isEditable && matchesLabel) {
            return root
        }
        return (0 until root.childCount)
            .asSequence()
            .mapNotNull { index -> root.getChild(index)?.let { find(it, labels) } }
            .firstOrNull()
    }

    fun await(
        vararg labels: String,
        timeoutMillis: Long = 15_000,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            node(*labels)?.takeIf { it.isVisibleToUser }?.let { return it }
            SystemClock.sleep(100)
        }
        error(
            "Missing owned application UI: ${labels.joinToString()}; " +
                "root=${automation.rootInActiveWindow?.packageName}; visible=" +
                nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | "),
        )
    }

    fun click(vararg labels: String) {
        tap(await(*labels))
    }

    fun activate(vararg labels: String) {
        fun clickable(): AccessibilityNodeInfo {
            var node = await(*labels)
            while (!node.isClickable) {
                node = requireNotNull(node.parent) { "No clickable ancestor for ${labels.joinToString()}" }
            }
            return node
        }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        var accepted = false
        while (!accepted && SystemClock.elapsedRealtime() < deadline) {
            val targetNode = clickable()
            accepted = targetNode.isEnabled && targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (!accepted) SystemClock.sleep(100)
        }
        check(accepted) {
            "Control did not accept action: ${labels.joinToString()}; " +
                nodes().mapNotNull { it.text ?: it.contentDescription }.joinToString(" | ")
        }
        SystemClock.sleep(400)
    }

    fun checkedChoice(vararg labels: String): Boolean {
        var targetNode = await(*labels)
        while (!targetNode.isCheckable) {
            targetNode = requireNotNull(targetNode.parent) { "No checkable ancestor for ${labels.joinToString()}" }
        }
        return targetNode.isChecked
    }

    fun toggleChoice() {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (nodes().singleOrNull { it.isCheckable }?.isEnabled != true &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(100)
        }
        val choice = nodes().single { it.isCheckable }
        check(choice.isEnabled) { "Choice is still disabled" }
        check(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Choice action was rejected" }
        SystemClock.sleep(300)
    }

    fun tap(node: AccessibilityNodeInfo) {
        check(node.packageName?.toString() == context.packageName)
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event =
                MotionEvent.obtain(
                    now,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(automation.injectInputEvent(event, true)) { "UI input was rejected" }
            } finally {
                event.recycle()
            }
        }
        SystemClock.sleep(300)
    }

    fun back() {
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        SystemClock.sleep(400)
    }

    fun ready() {
        launch()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            node("我已知晓，开始使用", "I understand, start using")?.let(::tap)
            if (node("应用导航", "App navigation") != null) return
            SystemClock.sleep(100)
        }
        await("应用导航", "App navigation")
    }
}

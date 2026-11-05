package com.helix.extensions.mobileuse.automation.backend

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.os.Looper
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationWindowRegion
import java.io.StringWriter

/** Shell-only platform bridge. No hidden API access or generated code in the main app process. */
@Suppress("TooManyFunctions") // One short-lived platform connection owns reads, input and cleanup.
internal class ShizukuHierarchy : AutoCloseable {
    private val thread = HandlerThread("helix-shizuku-observe").apply { start() }
    private var automation: UiAutomation? = null
    var displayId = 0
        private set
    private var displaySession = ""
    private var independentFocus = false
    private var capture: (() -> android.graphics.Bitmap?)? = null
    private var routeAlive: () -> Boolean = { true }

    fun route(display: ShellBackgroundDisplay?) {
        routeAlive = { display?.valid != false }
        check(routeAlive()) { "BACKGROUND_DISPLAY_STALE" }
        displayId = display?.id ?: 0
        displaySession = display?.identity.orEmpty()
        independentFocus = display?.independentFocus ?: false
        capture = display?.let { it::screenshot }
    }

    private fun displayWindows(): List<android.view.accessibility.AccessibilityWindowInfo> {
        check(routeAlive()) { "BACKGROUND_DISPLAY_STALE" }
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            checkNotNull(automation).windowsOnAllDisplays.get(displayId).orEmpty()
        } else {
            check(displayId == 0) { "BACKGROUND_REQUIRES_API_30" }
            checkNotNull(automation).windows
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        if (displayId == 0) return checkNotNull(automation).rootInActiveWindow
        val windows = displayWindows()
        return try {
            val candidates =
                windows
                    .filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .sortedWith(
                        compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isFocused }
                            .thenByDescending { it.layer },
                    )
            // A blind focused window must not be replaced by an unrelated readable background tree.
            val active =
                candidates.firstOrNull { it.isFocused }
                    ?: candidates.firstOrNull {
                        it.type ==
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                    }
            active?.root
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    fun visibleOnMain(packageName: String): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val windows = checkNotNull(automation).windowsOnAllDisplays.get(0).orEmpty()
        return try {
            windows.any { window ->
                val root = window.root
                try {
                    root?.packageName?.toString() == packageName
                } finally {
                    root?.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    fun scopedCapturePermitted(target: AutomationDisplayTarget): Boolean {
        if (displayId == 0 || target.bounds !=
            AutomationNodeBounds(0, 0, target.width, target.height)
        ) {
            return false
        }
        val windows = displayWindows()
        return try {
            windows.all { window ->
                val node = window.root
                try {
                    node?.packageName?.toString() == target.packageName
                } finally {
                    node?.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    // This platform bridge runs only as shell/root; hidden API compatibility failures remain visible.
    @android.annotation.SuppressLint("PrivateApi")
    fun connect() {
        check(android.os.Process.myUid() in setOf(0, 2000)) { "PRIVILEGED_PROCESS_REQUIRED" }
        val connectionType = Class.forName("android.app.IUiAutomationConnection")
        val connection = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val instance =
            UiAutomation::class.java
                .getConstructor(Looper::class.java, connectionType)
                .newInstance(thread.looper, connection)
        automation = instance
        UiAutomation::class.java
            .getMethod("connect", Int::class.javaPrimitiveType)
            .invoke(instance, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        instance.serviceInfo =
            checkNotNull(instance.serviceInfo).apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
    }

    fun read(): String {
        val instance = checkNotNull(automation)
        // setServiceInfo clears this UiAutomation connection's node cache on supported Android versions.
        instance.serviceInfo = checkNotNull(instance.serviceInfo)
        val root = checkNotNull(root()) { "HIERARCHY_UNAVAILABLE" }
        val writer = StringWriter()
        val xml = Xml.newSerializer().apply { setOutput(writer) }
        try {
            val rotation = rotation()
            xml.startTag(null, "hierarchy").attribute(null, "rotation", rotation.toString())

            HierarchyWriter(writer, xml).visit(root, 0)
            xml.endTag(null, "hierarchy")
            xml.flush()
            return writer.toString().also { check(it.length <= ShizukuUiTarget.MAX_XML_CHARS) }
        } finally {
            root.recycle()
        }
    }

    /** Recheck foreground identity and every reported window covering the point before injection. */
    @Suppress("ReturnCount") // Refuse each unverified screen boundary before any input is injected.
    fun permitsPoint(
        selector: ShizukuUiSelector,
        x: Int,
        y: Int,
        expectedRotation: Int,
    ): Boolean {
        val instance = checkNotNull(automation)
        instance.serviceInfo = checkNotNull(instance.serviceInfo)
        val root = root() ?: return false
        try {
            if (root.packageName?.toString() != selector.packageName || rotation() != expectedRotation) return false
            val bounds = Rect().also(root::getBoundsInScreen)
            if (!bounds.contains(x, y)) return false
            val windows = displayWindows()
            return try {
                val target = windows.singleOrNull { it.id == root.windowId } ?: return false
                if (android.os.Build.VERSION.SDK_INT >= 30 && target.displayId != displayId) return false
                windows.none { window ->
                    window.id != target.id && window.layer >= target.layer &&
                        coversInputPoint(window, x, y)
                }
            } finally {
                windows.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun coversInputPoint(
        window: android.view.accessibility.AccessibilityWindowInfo,
        x: Int,
        y: Int,
    ): Boolean {
        // IME bounds can enclose transparent holes; use its actual touchable region where available.
        // Other overlays retain the conservative visual-bounds guard, and API29 retains its old fallback.
        if (android.os.Build.VERSION.SDK_INT >= 30 &&
            window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        ) {
            return android.graphics
                .Region()
                .also(window::getRegionInScreen)
                .contains(x, y)
        }
        return Rect().also(window::getBoundsInScreen).contains(x, y)
    }

    private fun rotation(): Int = display().rotation

    fun gestureDisplay(): GestureDisplay {
        val display = display()
        val size = android.graphics.Point().also { display.getRealSize(it) }
        return GestureDisplay(display.displayId, size.x, size.y, display.rotation)
    }

    @android.annotation.SuppressLint("PrivateApi") // Same shell/root-only platform bridge as connect().
    private fun display(): android.view.Display {
        val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager = type.getMethod("getInstance").invoke(null)
        return type
            .getMethod("getRealDisplay", Int::class.javaPrimitiveType)
            .invoke(manager, displayId) as android.view.Display
    }

    fun deviceTarget(
        includeRevision: Boolean = true,
        allowVisualFallback: Boolean = false,
    ): AutomationDisplayTarget? {
        val instance = checkNotNull(automation)
        instance.serviceInfo = checkNotNull(instance.serviceInfo)
        val root = root() ?: return visualDeviceTarget(allowVisualFallback)
        return try {
            val size = android.graphics.Point().also { display().getRealSize(it) }
            val bounds = Rect().also(root::getBoundsInScreen)
            val window = displayWindows()
            try {
                val active = window.singleOrNull { it.id == root.windowId }
                if (active == null || (android.os.Build.VERSION.SDK_INT >= 30 && active.displayId != displayId)) {
                    null
                } else {
                    AutomationDisplayTarget(
                        root.packageName?.toString().orEmpty(),
                        root.windowId,
                        displayId,
                        size.x,
                        size.y,
                        rotation(),
                        AutomationNodeBounds(
                            bounds.left,
                            bounds.top,
                            bounds.right,
                            bounds.bottom,
                        ),
                        if (includeRevision) {
                            java.security.MessageDigest
                                .getInstance("SHA-256")
                                .digest(read().toByteArray())
                                .joinToString("") { "%02x".format(it) }
                        } else {
                            ""
                        },
                        displaySession,
                        independentFocus,
                    )
                }
            } finally {
                window.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    private fun visualDeviceTarget(wholeDisplay: Boolean): AutomationDisplayTarget? {
        if (android.os.Build.VERSION.SDK_INT < 31 || !wholeDisplay || displayId == 0) return null
        val windows = displayWindows()
        return try {
            val applicationWindows =
                windows
                    .filter {
                        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                            it.displayId == displayId
                    }.map {
                        val bounds = Rect().also(it::getBoundsInScreen)
                        VisualWindow(
                            it.id,
                            it.isFocused,
                            AutomationNodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        )
                    }
            val size = android.graphics.Point().also { display().getRealSize(it) }
            visualTarget(
                VisualDisplay(displayId, displaySession, size.x, size.y, rotation(), independentFocus),
                wholeDisplay,
                applicationWindows,
                visibleDisplayTasks(displayId),
            )
        } catch (failure: ReflectiveOperationException) {
            android.util.Log.w("HelixDisplay", "Visual task identity unavailable", failure)
            null
        } catch (failure: SecurityException) {
            android.util.Log.w("HelixDisplay", "Visual task identity denied", failure)
            null
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    fun windows(): List<AutomationWindowRegion> {
        val windows = displayWindows()
        return try {
            windows
                .filter { android.os.Build.VERSION.SDK_INT < 30 || it.displayId == displayId }
                .map {
                    val bounds = Rect().also(it::getBoundsInScreen)
                    AutomationWindowRegion(
                        it.id,
                        it.layer,
                        null,
                        AutomationNodeBounds(
                            bounds.left,
                            bounds.top,
                            bounds.right,
                            bounds.bottom,
                        ),
                    )
                }.sortedBy { it.windowId }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    @android.annotation.SuppressLint("PrivateApi")
    fun globalAction(action: AutomationGlobalAction): Boolean {
        if (displayId == 0) return checkNotNull(automation).performGlobalAction(action.platformId)
        val code =
            when (action) {
                AutomationGlobalAction.BACK -> android.view.KeyEvent.KEYCODE_BACK
                else -> null
            }
        return code != null && backgroundKey(code)
    }

    @android.annotation.SuppressLint("PrivateApi")
    private fun backgroundKey(code: Int): Boolean {
        val downTime = android.os.SystemClock.uptimeMillis()

        fun inject(kind: Int): Boolean {
            check(routeAlive()) { "BACKGROUND_DISPLAY_STALE" }
            val event = android.view.KeyEvent(downTime, android.os.SystemClock.uptimeMillis(), kind, code, 0)
            android.view.InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
            return checkNotNull(automation).injectInputEvent(event, true)
        }
        var up = false
        val down =
            try {
                inject(android.view.KeyEvent.ACTION_DOWN)
            } finally {
                up = inject(android.view.KeyEvent.ACTION_UP)
            }
        return down && up
    }

    fun screenshot(): android.graphics.Bitmap? =
        if (displayId ==
            0
        ) {
            checkNotNull(automation).takeScreenshot()
        } else {
            capture?.invoke()
        }

    @android.annotation.SuppressLint("PrivateApi")
    fun synchronizeWindowTransactions() {
        // View detachment and app Choreographer frames do not fence WindowManager's surface removal.
        // This existing shell/root-only bridge waits for window operations, not app content to become idle.
        UiAutomation::class.java.getMethod("syncInputTransactions").invoke(checkNotNull(automation))
    }

    fun freshRoot(): AccessibilityNodeInfo? {
        val instance = checkNotNull(automation)
        instance.serviceInfo = checkNotNull(instance.serviceInfo)
        return root()
    }

    @android.annotation.SuppressLint("PrivateApi")
    fun inject(event: android.view.MotionEvent): Boolean {
        check(routeAlive()) { "BACKGROUND_DISPLAY_STALE" }
        if (displayId != 0) {
            android.view.InputEvent::class.java
                .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
        return checkNotNull(automation).injectInputEvent(event, true)
    }

    override fun close() {
        try {
            automation?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) }
        } finally {
            thread.quitSafely()
            thread.join(2000)
        }
    }
}

private class HierarchyWriter(
    private val writer: StringWriter,
    private val xml: org.xmlpull.v1.XmlSerializer,
) {
    private var count = 0

    fun visit(
        node: AccessibilityNodeInfo,
        depth: Int,
    ) {
        check(
            ++count <= 2000 && depth <= 64 && writer.buffer.length <= ShizukuUiTarget.MAX_XML_CHARS,
        ) { "HIERARCHY_LIMIT" }
        val bounds = Rect().also(node::getBoundsInScreen)
        xml.startTag(null, "node")
        for ((name, value) in mapOf(
            "package" to node.packageName?.toString().orEmpty(),
            "resource-id" to node.viewIdResourceName.orEmpty(),
            "class" to node.className?.toString().orEmpty(),
            "content-desc" to
                if (node.isPassword) {
                    ""
                } else {
                    node.contentDescription
                        ?.toString()
                        .orEmpty()
                        .take(2000)
                },
            "editable" to node.isEditable.toString(),
            "scrollable" to node.isScrollable.toString(),
            "long-clickable" to node.isLongClickable.toString(),
            "checkable" to node.isCheckable.toString(),
            "checked" to node.isChecked.toString(),
            "text" to
                if (node.isPassword) {
                    ""
                } else {
                    node.text
                        ?.toString()
                        .orEmpty()
                        .take(2000)
                },
            "enabled" to node.isEnabled.toString(),
            "clickable" to (node.isClickable && node.isVisibleToUser).toString(),
            "password" to node.isPassword.toString(),
            "bounds" to "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
        )) {
            xml.attribute(null, name, value)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        xml.endTag(null, "node")
    }
}

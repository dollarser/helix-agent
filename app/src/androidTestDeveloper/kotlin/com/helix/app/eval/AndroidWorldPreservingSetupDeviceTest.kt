package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit, separately authorized setup for an isolated benchmark emulator only. */
class AndroidWorldPreservingSetupDeviceTest {
    @Test
    fun configureAuthorizedScope() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("helix.authorizedBenchmarkScope") == "true")
        val expectedAvd = requireNotNull(args.getString("avdName"))
        require(expectedAvd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val actualAvd =
            automation.executeShellCommand("getprop ro.boot.qemu.avd_name").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { String(it.readBytes()).trim() }
            }
        check(actualAvd == expectedAvd) { "Only the explicitly designated benchmark emulator is permitted" }
        val packages = requireNotNull(args.getString("packages")).split(',').toSet()
        require(
            packages.isNotEmpty() &&
                packages.all { it == "android" || it.matches(Regex("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")) },
        )
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        check(
            container.storage.turns
                .listActive()
                .isEmpty(),
        ) { "Active turns must settle before setup" }
        val catalog = container.pluginService.catalog
        val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
        val grants = MobileUseGrantStore(lines::lines, lines::setLines, { catalog.selectionId(it, "mobile-use") })
        grants.configureExecution(background = false, allowMoveFromMain = false)
        grants.configureGlobal(packages, wholePhone = false, shareScreens = true)
        check(grants.globalConfiguration()?.scope?.allowedPackages == packages)
    }

    @Test
    fun dumpNativeUi() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("helix.nativeUiDump") == "true")
        val expectedAvd = requireNotNull(args.getString("avdName"))
        require(expectedAvd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
        val runId = requireNotNull(args.getString("runId"))
        val dumpId = requireNotNull(args.getString("dumpId"))
        require(listOf(runId, dumpId).all { it.matches(Regex("[A-Za-z0-9_-]{1,120}")) })
        val automation =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val actualAvd =
            automation.executeShellCommand("getprop ro.boot.qemu.avd_name").use {
                java.io.FileInputStream(it.fileDescriptor).use { stream -> String(stream.readBytes()).trim() }
            }
        check(actualAvd == expectedAvd) { "Only the designated benchmark emulator is permitted" }
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        check(
            app.appContainer.storage.turns
                .listActive()
                .isEmpty(),
        ) { "No dump during active model turns" }
        val destination = java.io.File(app.filesDir, "androidworld-native/$runId/$dumpId.xml")
        check(!destination.exists()) { "Never overwrite native evidence" }
        NativeDump().write(automation, destination)
    }

    private class NativeDump {
        private val writer = java.io.StringWriter()
        private val xml = android.util.Xml.newSerializer()
        private var count = 0

        fun write(
            automation: android.app.UiAutomation,
            destination: java.io.File,
        ) {
            xml.setOutput(writer)
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "hierarchy")
            val info = automation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            val windows = automation.windows
            check(windows.isNotEmpty()) { "No accessibility windows available" }
            windows.forEach(::writeWindow)
            check(count > 0) { "No accessibility nodes available" }
            xml.endTag(null, "hierarchy")
            xml.endDocument()
            destination.parentFile!!.mkdirs()
            destination.writeText(writer.toString())
        }

        private fun node(
            value: android.view.accessibility.AccessibilityNodeInfo,
            index: Int,
            depth: Int,
        ) {
            check(++count <= 10000 && depth <= 100) { "Native tree exceeds bounded dump" }
            xml.startTag(null, "node")
            writeAttributes(value, index)
            for (childIndex in 0 until value.childCount) {
                value.getChild(childIndex)?.let { child ->
                    try {
                        node(child, childIndex, depth + 1)
                    } finally {
                        child.recycle()
                    }
                }
            }
            xml.endTag(null, "node")
        }

        private fun writeAttributes(
            value: android.view.accessibility.AccessibilityNodeInfo,
            index: Int,
        ) {
            val bounds = android.graphics.Rect()
            value.getBoundsInScreen(bounds)
            val attributes =
                mapOf(
                    "index" to index.toString(),
                    "text" to value.text?.toString().orEmpty(),
                    "resource-id" to value.viewIdResourceName.orEmpty(),
                    "class" to value.className?.toString().orEmpty(),
                    "package" to value.packageName?.toString().orEmpty(),
                    "content-desc" to value.contentDescription?.toString().orEmpty(),
                    "enabled" to value.isEnabled.toString(),
                    "clickable" to value.isClickable.toString(),
                    "checkable" to value.isCheckable.toString(),
                    "checked" to value.isChecked.toString(),
                    "focusable" to value.isFocusable.toString(),
                    "focused" to value.isFocused.toString(),
                    "scrollable" to value.isScrollable.toString(),
                    "long-clickable" to value.isLongClickable.toString(),
                    "password" to value.isPassword.toString(),
                    "selected" to value.isSelected.toString(),
                    "bounds" to "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]",
                )
            attributes.forEach { (key, content) -> xml.attribute(null, key, content) }
        }

        private fun writeWindow(window: android.view.accessibility.AccessibilityWindowInfo) {
            try {
                window.root?.let { root ->
                    try {
                        xml.startTag(null, "window")
                        xml.attribute(null, "id", window.id.toString())
                        xml.attribute(null, "active", window.isActive.toString())
                        node(root, 0, 0)
                        xml.endTag(null, "window")
                    } finally {
                        root.recycle()
                    }
                }
            } finally {
                window.recycle()
            }
        }
    }
}

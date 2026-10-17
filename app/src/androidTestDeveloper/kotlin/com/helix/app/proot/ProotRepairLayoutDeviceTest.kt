package com.helix.app.proot

import android.content.Intent
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.runtime.proot.app.ProotRepairActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProotRepairLayoutDeviceTest : com.helix.app.test.ForegroundDeviceTestHost() {
    @Test fun deletingRequiresConfirmationAndCancellationKeepsInstallation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ensureInstalledRuntime(context)
        val original =
            com.helix.runtime.proot.client
                .ProotRuntimeSupervisor(context)
                .installation()
                .manifest
        val marker = createExternalMarker(context)

        fun text(id: Int) = context.getString(id)

        fun click(label: String) {
            waitForText(label)
            val node =
                instrumentation.uiAutomation.rootInActiveWindow
                    .findAccessibilityNodeInfosByText(label)
                    .first { it.isClickable }
            assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        context.startActivity(Intent(context, ProotRepairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val more = text(com.helix.runtime.proot.app.R.string.proot_environment_more)
            val remove = text(com.helix.runtime.proot.app.R.string.proot_repair_remove)
            click(more)
            click(remove)
            click(text(android.R.string.cancel))
            org.junit.Assert.assertEquals(
                original,
                com.helix.runtime.proot.client
                    .ProotRuntimeSupervisor(context)
                    .installation()
                    .manifest,
            )
            click(remove)
            click(text(com.helix.runtime.proot.app.R.string.proot_environment_confirm))
            val success = text(com.helix.runtime.proot.app.R.string.proot_remove_success)
            waitForText(success)
            org.junit.Assert.assertEquals("HXA305_KEEP", marker.readText())
            assertTrue(
                !com.helix.runtime.proot.client
                    .ProotRuntimeSupervisor(context)
                    .installationReady(),
            )
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            context.startActivity(
                Intent(context, ProotRepairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitForText(success)
        } finally {
            marker.parentFile!!.deleteRecursively()
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
        }
    }

    private fun createExternalMarker(context: android.content.Context): java.io.File {
        val external =
            java.nio.file.Files
                .createTempDirectory(context.cacheDir.toPath(), "environment-delete-")
                .toFile()
        val marker = java.io.File(external, "keep.txt").apply { writeText("HXA305_KEEP") }
        java.nio.file.Files.createSymbolicLink(
            java.io
                .File(
                    com.helix.runtime.proot.app.ProotRuntimeInstaller
                        .runtimeRoot(context),
                    "external-link",
                ).toPath(),
            external.toPath(),
        )
        return marker
    }

    @Test fun legalPageKeepsTitleAndCloseInsideSystemBars() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val title = context.getString(com.helix.runtime.proot.app.R.string.proot_legal_title)
        val done = context.getString(com.helix.runtime.proot.app.R.string.proot_legal_done)
        context.startActivity(
            Intent(context, com.helix.runtime.proot.app.ProotLegalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            waitForText(title)
            waitForText(done)
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            val root = requireNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val titleBounds = Rect().also(root.findAccessibilityNodeInfosByText(title).first()::getBoundsInScreen)
            val close = root.findAccessibilityNodeInfosByText(done).first()
            val closeBounds = Rect().also(close::getBoundsInScreen)
            val window = Rect().also(root::getBoundsInScreen)
            assertTrue(titleBounds.top >= 40 * context.resources.displayMetrics.density)
            assertTrue(close.isVisibleToUser && closeBounds.bottom < window.bottom)
            assertTrue(closeBounds.top > titleBounds.bottom)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "proot-legal-layout.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
            assertTrue(close.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        } finally {
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
        }
    }

    private fun waitForText(text: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (!ui.rootInActiveWindow?.findAccessibilityNodeInfosByText(text).isNullOrEmpty()) return
            Thread.sleep(100)
        }
        error("Expected Runtime text was not displayed")
    }

    @Test fun repairContentStaysBelowStatusBar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val title = context.getString(com.helix.runtime.proot.app.R.string.proot_repair_title)
        context.startActivity(Intent(context, ProotRepairActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val deadline = System.currentTimeMillis() + 10_000
            var bounds: Rect? = null
            while (bounds == null && System.currentTimeMillis() < deadline) {
                val nodes = instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(title)
                bounds = nodes?.firstOrNull()?.let { node -> Rect().also(node::getBoundsInScreen) }
                if (bounds == null) Thread.sleep(100)
            }
            assertTrue("Repair title is visible in its private process", bounds != null)
            val density = context.resources.displayMetrics.density
            assertTrue("Title clears the status bar and content padding", requireNotNull(bounds).top >= 40 * density)
            // Accessibility may publish the title before the private Activity's opening frame settles.
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            Thread.sleep(1_000) // Allow the platform window fade to finish before visual evidence.
            waitForText(title)
            val screenshot = instrumentation.uiAutomation.takeScreenshot()
            java.io.File(context.cacheDir, "proot-repair-layout.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            screenshot.recycle()
        } finally {
            instrumentation.uiAutomation.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
            )
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
        }
    }
}

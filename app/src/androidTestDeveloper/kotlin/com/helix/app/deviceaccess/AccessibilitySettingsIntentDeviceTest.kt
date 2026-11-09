package com.helix.app.deviceaccess

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.tools.deviceaccess.DeviceAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class AccessibilitySettingsIntentDeviceTest {
    @Test fun publicSettingsRouteHasOnlyAnAdvisoryServiceRowHint() {
        val component = ComponentName("com.helix.agent.developer", "example.AccessibilityService")
        val intent = DeviceAccess.accessibilitySettingsIntent(component)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertNull(intent.component)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(
            component.flattenToString(),
            intent
                .getBundleExtra(":settings:show_fragment_args")
                ?.getString(":settings:fragment_args_key"),
        )
        assertNull(DeviceAccess.accessibilitySettingsIntent().extras)
    }

    @Test fun publicSettingsEntryShowsOwnServiceForVisualReview() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("helix.accessibilitySettingsVisual") == "true")
        val avd = requireNotNull(args.getString("avdName"))
        val runId = requireNotNull(args.getString("runId"))
        require(avd.matches(Regex("Helix_Benchmark_[A-Za-z0-9_]+")))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val ui =
            InstrumentationRegistry
                .getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertEquals("1", shell(ui, "getprop ro.kernel.qemu").trim())
        assertEquals(avd, shell(ui, "getprop ro.boot.qemu.avd_name").trim())
        assertTrue(
            app.appContainer.storage.turns
                .listActive()
                .isEmpty(),
        )
        reviewSettingsEntry(ui, app, runId, avd)
    }

    private fun reviewSettingsEntry(
        ui: UiAutomation,
        app: HelixApplication,
        runId: String,
        avd: String,
    ) {
        val component =
            ComponentName(
                app.packageName,
                "com.helix.extensions.mobileuse.automation.HelixAccessibilityService",
            )
        val info = app.packageManager.getServiceInfo(component, 0)
        val label = info.loadLabel(app.packageManager).toString()
        val intent = DeviceAccess.accessibilitySettingsIntent(component)
        val settingsPackage = requireNotNull(intent.resolveActivity(app.packageManager)).packageName
        val before = permissionState(ui)
        val directory = File(app.filesDir, "accessibility-recovery/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Never overwrite visual evidence" }
        var launched = false
        try {
            app.startActivity(intent)
            launched = true
            awaitVisibleLabel(ui, settingsPackage, label)
            captureRenderedFrames(ui, directory, settingsPackage, label)
            File(directory, "receipt.txt").writeText(
                "runId=$runId\navd=$avd\naction=${intent.action}\ncomponent=${component.flattenToString()}\n" +
                    "label=$label\nsettingsPackage=$settingsPackage\nserviceLabelVisible=true\n" +
                    "pixelCheck=nonUniformLabelRegionOnly\nhighlightStatus=unverified\n" +
                    "componentRowIdentityRequiresHumanVisualReview=true\nhighlightRequiresHumanVisualReview=true\n",
            )
        } finally {
            try {
                if (launched) {
                    assertTrue(
                        ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),
                    )
                    SystemClock.sleep(500)
                }
            } finally {
                assertEquals(before, permissionState(ui))
                assertTrue(
                    app.appContainer.storage.turns
                        .listActive()
                        .isEmpty(),
                )
            }
        }
    }

    private fun awaitVisibleLabel(
        ui: UiAutomation,
        settingsPackage: String,
        label: String,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (visibleLabelBounds(ui, settingsPackage, label) != null) return
            SystemClock.sleep(100)
        }
        error("Own service row was not visible in public Accessibility settings")
    }

    /** Preserve early animation frames, but never promote a tree-visible blank surface to evidence. */
    private fun captureRenderedFrames(
        ui: UiAutomation,
        directory: File,
        settingsPackage: String,
        label: String,
    ) {
        val started = SystemClock.elapsedRealtime()
        val receipt = StringBuilder("elapsedMs,file,labelPixelsRendered,stableBounds\n")
        var priorRenderedBounds: android.graphics.Rect? = null
        val primary = File(directory, "settings-service-row.png")
        for ((index, offset) in listOf(0L, 100L, 250L, 450L, 750L, 1_100L, 1_600L, 2_200L).withIndex()) {
            val wait = started + offset - SystemClock.elapsedRealtime()
            if (wait > 0) SystemClock.sleep(wait)
            val bounds = visibleLabelBounds(ui, settingsPackage, label)
            if (bounds == null) {
                priorRenderedBounds = null
                receipt.append("${SystemClock.elapsedRealtime() - started},not-captured,false,false\n")
                continue
            }
            val raw = requireNotNull(ui.takeScreenshot()) { "Settings screenshot unavailable" }
            val bitmap =
                try {
                    requireNotNull(raw.copy(android.graphics.Bitmap.Config.ARGB_8888, false))
                } finally {
                    raw.recycle()
                }
            try {
                val afterBounds = visibleLabelBounds(ui, settingsPackage, label)
                val rendered = afterBounds == bounds && hasLabelPixels(bitmap, bounds)
                val stable = rendered && priorRenderedBounds == bounds
                val name = "settings-frame-$index.png"
                writeBitmap(bitmap, File(directory, name))
                receipt.append("${SystemClock.elapsedRealtime() - started},$name,$rendered,$stable\n")
                if (stable && !primary.exists()) writeBitmap(bitmap, primary)
                priorRenderedBounds = bounds.takeIf { rendered }
            } finally {
                bitmap.recycle()
            }
        }
        File(directory, "frames.csv").writeText(receipt.toString())
        check(primary.exists()) { "No two rendered service-label frames; inspect frames.csv and numbered frames" }
    }

    private fun visibleLabelBounds(
        ui: UiAutomation,
        settingsPackage: String,
        label: String,
    ): android.graphics.Rect? {
        val root = ui.rootInActiveWindow ?: return null
        return try {
            if (root.packageName?.toString() == settingsPackage) labelBounds(root, label) else null
        } finally {
            root.recycle()
        }
    }

    private fun labelBounds(
        root: android.view.accessibility.AccessibilityNodeInfo,
        label: String,
    ): android.graphics.Rect? {
        val matches = root.findAccessibilityNodeInfosByText(label)
        return try {
            matches
                .firstOrNull {
                    it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
                }?.let { node ->
                    android.graphics
                        .Rect()
                        .also(node::getBoundsInScreen)
                        .takeUnless { it.isEmpty }
                }
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    private fun hasLabelPixels(
        bitmap: android.graphics.Bitmap,
        bounds: android.graphics.Rect,
    ): Boolean {
        if (bounds.isEmpty || !android.graphics.Rect(0, 0, bitmap.width, bitmap.height).contains(bounds)) {
            return false
        }
        var count = 0
        var sum = 0.0
        var squares = 0.0
        for (y in bounds.top until bounds.bottom step 2) {
            for (x in bounds.left until bounds.right step 2) {
                val color = bitmap.getPixel(x, y)
                val value =
                    (
                        android.graphics.Color.red(color) + android.graphics.Color.green(color) +
                            android.graphics.Color.blue(color)
                    ) / 3.0
                count++
                sum += value
                squares += value * value
            }
        }
        // A flat transition surface is not rendered text. Human QA must still assess text/highlighting.
        return count >= 16 && squares / count - (sum / count) * (sum / count) >= 64.0
    }

    private fun writeBitmap(
        bitmap: android.graphics.Bitmap,
        file: File,
    ) {
        check(file.createNewFile()) { "Never overwrite screenshot evidence" }
        file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun blankLabelFramesCannotBecomeStableVisualEvidence() {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 20, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val bounds = android.graphics.Rect(0, 0, 40, 20)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            org.junit.Assert.assertFalse(hasLabelPixels(bitmap, bounds))
            for (x in 10 until 30) for (y in 4 until 16) bitmap.setPixel(x, y, android.graphics.Color.BLACK)
            assertTrue(hasLabelPixels(bitmap, bounds))
            org.junit.Assert.assertFalse(hasLabelPixels(bitmap, android.graphics.Rect(-1, 0, 40, 20)))
        } finally {
            bitmap.recycle()
        }
    }

    private fun permissionState(ui: UiAutomation): Pair<String, String> =
        shell(ui, "settings get secure enabled_accessibility_services").trim() to
            shell(ui, "settings get secure accessibility_enabled").trim()

    private fun shell(
        ui: UiAutomation,
        command: String,
    ): String =
        ui.executeShellCommand(command).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes(), Charsets.UTF_8) }
        }
}

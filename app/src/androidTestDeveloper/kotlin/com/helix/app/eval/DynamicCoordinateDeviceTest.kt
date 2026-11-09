package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.automation.AutomationModule
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** External shell backend + real custom view. The fixture facts verify effects independently of the reply. */
class DynamicCoordinateDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fixturePackage = instrumentation.context.packageName
    private val uri = Uri.parse("content://$fixturePackage.dynamic-coordinate")

    @Test fun fixtureRenders() {
        launch()
        assertTrue(fact("ticks") > 0)
        assertTrue(fact("x") > 0)
        assertEquals(0, fact("clicks"))
    }

    @Suppress("LongMethod") // One journey retains observation identity and independent effect evidence.
    @Test
    fun productionBackendExecutesOnChangingVideoAndRetainsWindowBoundary() {
        require(InstrumentationRegistry.getArguments().getString("helixDynamicCoordinates") == "true")
        awaitComputerAdbHelper()
        launch()
        val backend = requireNotNull(AutomationModule.computerAdbBackend)
        val evidence = java.io.File(context.filesDir, "direct-coordinate-evidence").apply { mkdirs() }

        fun observe(waitForFixture: Boolean = false): AutomationDisplayTarget {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var status: String
            do {
                val reply =
                    backend.device(
                        AutomationDeviceRequest(AutomationDeviceOperation.OBSERVE, wholeDisplay = true),
                    ) { true }
                status = reply.status
                val target = reply.target
                if (target != null && (!waitForFixture || target.packageName == fixturePackage)) return target
                check(waitForFixture) { "Observation unavailable: $status" }
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            error("Fixture window did not become available: $status")
        }

        fun capture(
            target: AutomationDisplayTarget,
            name: String,
        ): ByteArray {
            val result =
                backend.device(
                    AutomationDeviceRequest(AutomationDeviceOperation.SCREENSHOT, target, wholeDisplay = true),
                ) { true }
            assertEquals("Capture $name for $target returned ${result.target}", "SAVED", result.status)
            assertEquals(target, result.target)
            return requireNotNull(result.screenshot?.png).also {
                java.io.File(evidence, name).writeBytes(it)
            }
        }

        fun gesture(
            target: AutomationDisplayTarget,
            twice: Boolean = false,
        ): String {
            val point = listOf(AutomationPoint(fact("x").toFloat(), fact("y").toFloat()))
            val strokes =
                listOf(AutomationStroke(point, 0, 80)) +
                    if (twice) listOf(AutomationStroke(point, 160, 80)) else emptyList()
            return backend
                .device(
                    AutomationDeviceRequest(AutomationDeviceOperation.GESTURE, target, strokes, true),
                ) { true }
                .status
        }

        val original = observe(waitForFixture = true)
        val first = capture(original, "observed.png")
        val beforeTick = fact("ticks")
        SystemClock.sleep(250)
        val second = capture(observe(), "animated.png")
        assertTrue(fact("ticks") > beforeTick)
        assertEquals(original, observe())
        assertTargetPixelChanged(first, second)
        assertEquals("DISPATCHED", gesture(original))
        SystemClock.sleep(150)
        assertEquals(1, fact("clicks"))
        assertTrue("Admission must not compress physical press duration", fact("pressMillis") >= 60)

        context.contentResolver.call(uri, "mode", "overlay", null)
        SystemClock.sleep(250)
        val overlay = observe()
        capture(overlay, "overlay.png")
        assertEquals("DISPATCHED", gesture(overlay, twice = true))
        SystemClock.sleep(150)
        assertEquals(3, fact("clicks"))
        assertEquals(1, fact("doubleTaps"))
        assertTrue(fact("downGapMillis") in 100..300)

        // Explicit accepted limitation: a same-window custom content change is not a coordinate refusal.
        val beforeModal = observe()
        capture(beforeModal, "before-modal.png")
        context.contentResolver.call(uri, "mode", "modal", null)
        SystemClock.sleep(250)
        assertEquals(beforeModal, observe())
        assertEquals("DISPATCHED", gesture(beforeModal))
        SystemClock.sleep(150)
        assertEquals(4, fact("clicks"))
        capture(observe(), "modal.png")

        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        SystemClock.sleep(600)
        assertNotEquals(original.windowId, observe().windowId)
        assertEquals("TARGET_CHANGED", gesture(original))
        assertEquals(4, fact("clicks"))
        java.io.File(evidence, "result.txt").writeText(
            "backend=adb; changingTarget=DISPATCHED; transparentOverlayDoubleTap=DISPATCHED; " +
                "doubleTaps=1; sameWindowContent=DISPATCHED; differentWindow=TARGET_CHANGED; clicks=4\n",
        )
        println("DIRECT_COORDINATE_PASS clicks=4 doubleTaps=1")
    }

    private fun assertTargetPixelChanged(
        first: ByteArray,
        second: ByteArray,
    ) {
        val before = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(first, 0, first.size))
        val after = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(second, 0, second.size))
        try {
            assertNotEquals(before.getPixel(fact("x"), fact("y")), after.getPixel(fact("x"), fact("y")))
        } finally {
            before.recycle()
            after.recycle()
        }
    }

    private fun launch() {
        val launchId = (SystemClock.elapsedRealtime() % Int.MAX_VALUE).toInt()
        context.startActivity(
            Intent()
                .setComponent(ComponentName(fixturePackage, DynamicCoordinateFixtureActivity::class.java.name))
                .putExtra("launchId", launchId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (fact("launchId") == launchId && fact("x") > 0 && fact("ticks") > 0) return
            SystemClock.sleep(50)
        }
        error("Fixture did not draw the requested launch within 20 seconds")
    }

    private fun fact(name: String): Int =
        requireNotNull(context.contentResolver.query(uri, null, null, null, null)).use {
            check(it.moveToFirst())
            it.getInt(it.getColumnIndexOrThrow(name))
        }
}

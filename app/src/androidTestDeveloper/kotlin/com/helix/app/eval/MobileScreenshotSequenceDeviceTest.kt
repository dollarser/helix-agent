package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class MobileScreenshotSequenceDeviceTest {
    @Suppress("LongMethod") // Assert timing, output identity and native pixels from one production call.
    @Test
    fun dynamicPixelsAreCapturedInOneCallWithRealIntervalsAndNoCoordinateFrame() {
        require(InstrumentationRegistry.getArguments().getString("helixScreenshotSequence") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val fixture = MobileInteractionFixture()
        val target = InstrumentationRegistry.getInstrumentation().context.packageName
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val directory = File(app.filesDir, "mobile-sequence-evidence").apply { mkdirs() }
        MobileInteractionSession(app).use { session ->
            val launchId = System.nanoTime().toInt()
            app.startActivity(
                Intent()
                    .setComponent(ComponentName(target, DynamicCoordinateFixtureActivity::class.java.name))
                    .putExtra("launchId", launchId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            fixture.await("animated fixture") {
                app.contentResolver
                    .query(
                        Uri.parse("content://$target.dynamic-coordinate"),
                        null,
                        null,
                        null,
                        null,
                    )?.use {
                        it.moveToFirst() && it.getInt(it.getColumnIndexOrThrow("launchId")) == launchId &&
                            it.getInt(it.getColumnIndexOrThrow("ticks")) > 12
                    } == true
            }
            fixture.await("animated fixture exposed in the accessibility window") {
                session.output("ui.snapshot")["nodes"]?.jsonArray?.any {
                    it.jsonObject["text"]
                        ?.jsonPrimitive
                        ?.content
                        ?.startsWith("Comment ") == true
                } == true
            }
            var device = JsonObject(emptyMap())
            fixture.await("new fixture window at its final full-screen bounds") {
                device = session.output("ui.device")
                device["packageName"]?.jsonPrimitive?.content == target &&
                    device["windowBounds"] == device["screenBounds"]
            }
            val frame = device.getValue("frame").jsonPrimitive.content
            val before = Instant.now()
            val result =
                session.output(
                    "ui.screenshot",
                    fixture.json("""{"frame":"$frame","count":4,"intervalMillis":700}"""),
                )
            val after = Instant.now()
            File(
                directory,
                "sequence.json",
            ).writeText(
                JSONObject(result.toString())
                    .put("device", JSONObject(device.toString()))
                    .put("timings", session.timings)
                    .toString(2),
            )
            assertEquals("SAVED", result.getValue("status").jsonPrimitive.content)
            assertEquals("true", result.getValue("pixelsAttached").jsonPrimitive.content)
            assertEquals("contact_sheet", result.getValue("captureKind").jsonPrimitive.content)
            assertFalse(result.containsKey("frame"))
            assertFalse(result.containsKey("screenBounds"))
            val samples = result.getValue("samples").jsonArray.map { it.jsonObject }
            assertEquals(4, samples.size)
            for (sample in samples) {
                assertFalse(Instant.parse(sample.getValue("requestStartedAt").jsonPrimitive.content).isBefore(before))
                assertFalse(Instant.parse(sample.getValue("requestFinishedAt").jsonPrimitive.content).isAfter(after))
                assertTrue(sample.getValue("durationMillis").jsonPrimitive.long >= 0)
            }
            samples.zipWithNext().forEach { (first, second) ->
                assertTrue(
                    second.getValue("offsetMillis").jsonPrimitive.long -
                        first.getValue("offsetMillis").jsonPrimitive.long >=
                        first.getValue("durationMillis").jsonPrimitive.long + 690,
                )
            }
            val reference = result.getValue("reference").jsonPrimitive.content
            require(reference.startsWith("scope:app:output/"))
            val source = app.filesDir.resolve("workspaces/app/${reference.removePrefix("scope:app:")}")
            source.copyTo(File(directory, "sheet.png"), overwrite = true)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path))
            try {
                val tileWidth = bitmap.width / 2
                val tileHeight = bitmap.height / 2
                // This point is inside the fixture's animated canvas and below its text, in all four tiles.
                val scale =
                    (tileHeight - 32).toDouble() /
                        device
                            .getValue("screenBounds")
                            .jsonObject
                            .getValue("bottom")
                            .jsonPrimitive.long
                val y = 32 + (260 * scale).toInt()
                val colors =
                    (0..3).map { index ->
                        bitmap.getPixel(
                            index % 2 * tileWidth + tileWidth / 2,
                            index / 2 * tileHeight + y,
                        )
                    }
                File(directory, "colors.json").writeText(colors.toString())
                assertTrue("All frames must not be copies of one image: $colors", colors.distinct().size > 1)
            } finally {
                bitmap.recycle()
            }
        }
    }
}

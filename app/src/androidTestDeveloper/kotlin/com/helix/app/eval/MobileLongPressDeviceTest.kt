package com.helix.app.eval

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

/** Bounded local reproduction: callback completion and received touch/long-click effects are separate facts. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class MobileLongPressDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val fixture = MobileInteractionFixture()

    @Test fun accessibilityHoldAndSemanticSelectionHaveDistinctOutcomes() {
        require(InstrumentationRegistry.getArguments().getString("helixLongPress") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val report = JSONArray()
        try {
            MobileInteractionSession(app, setOf("com.google.android.documentsui")).use { session ->
                for (duration in listOf(700, 900, 1300, 1800)) {
                    report.put(holdDocument(session, duration))
                }
            }
        } finally {
            val directory = File(app.filesDir, "mobile-long-press-evidence").apply { mkdirs() }
            File(directory, "documents.json").writeText(report.toString(2))
        }
        assertEquals(4, report.length())
        val last = report.getJSONObject(report.length() - 1)
        val reference = LongPressReferenceInput.compare(last.getInt("x"), last.getInt("y"))
        File(app.filesDir, "mobile-long-press-evidence/reference.json").writeText(reference.toString(2))
        for (index in 0 until report.length()) {
            val dispatch = report.getJSONObject(index).getJSONObject("dispatch")
            assertEquals("DISPATCHED", dispatch.getString("status"))
            assertEquals("UNVERIFIED", dispatch.getString("effectStatus"))
        }
        verifySemanticSelection()
    }

    private fun prepareDocument(session: MobileInteractionSession): JsonObject {
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    Uri.parse("content://com.android.externalstorage.documents/root/primary"),
                    "vnd.android.document/root",
                ).setPackage("com.google.android.documentsui")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        SystemClock.sleep(1000)
        LongPressReferenceInput.openFixtureDirectory()
        var snapshot: JsonObject? = null
        fixture.await("DocumentsUI file after folder navigation") {
            snapshot = session.output("ui.snapshot")
            snapshot?.get("nodes")?.jsonArray?.any {
                it.jsonObject["text"]?.jsonPrimitive?.content == "helix_long_press_fixture.jpg"
            } == true
        }
        SystemClock.sleep(300)
        return checkNotNull(snapshot)
    }

    private fun holdDocument(
        session: MobileInteractionSession,
        duration: Int,
    ): JSONObject {
        val nodes = prepareDocument(session).getValue("nodes").jsonArray.map { it.jsonObject }
        val label = nodes.single { it["text"]?.jsonPrimitive?.content == "helix_long_press_fixture.jpg" }
        val target = if (duration == 700) label else nodes.single { it["token"] == label["clickTargetToken"] }
        val bounds = target.getValue("bounds").jsonObject

        fun coordinate(key: String) =
            bounds
                .getValue(key)
                .jsonPrimitive.content
                .toInt()
        val x = (coordinate("left") + coordinate("right")) / 2
        val y = (coordinate("top") + coordinate("bottom")) / 2
        val device = observeDevice(session)
        assertEquals("accessibility", device.getValue("clickMatchBackend").jsonPrimitive.content)
        val frame = device.getValue("frame").jsonPrimitive.content
        val gesture = """{"frame":"$frame","pattern":{"kind":"long_press",
            "center":{"x":$x,"y":$y},"durationMillis":$duration}}"""
        val result = session.output("ui.gesture", fixture.json(gesture))
        val expectation = """{"condition":"present","text":"selected","match":"contains","timeoutMillis":1000}"""
        val wait = session.output("ui.wait", fixture.json(expectation))
        val after = session.output("ui.snapshot")
        return JSONObject()
            .put("durationMillis", duration)
            .put("x", x)
            .put("y", y)
            .put("dispatch", JSONObject(result.toString()))
            .put("wait", JSONObject(wait.toString()))
            .put("snapshot", JSONObject(after.toString()))
    }

    private fun verifySemanticSelection() {
        MobileInteractionSession(app, setOf("com.google.android.documentsui")).use { session ->
            observeDevice(session)
            SystemClock.sleep(1000)
            val snapshot = session.output("ui.snapshot")
            File(app.filesDir, "mobile-long-press-evidence/semantic-before.json").writeText(snapshot.toString())
            assertTrue(
                snapshot.getValue("nodes").jsonArray.none {
                    it.jsonObject["text"]?.jsonPrimitive?.content == "1 selected"
                },
            )
            val target =
                snapshot.getValue("nodes").jsonArray.map { it.jsonObject }.single {
                    it["viewId"]?.jsonPrimitive?.content == "com.google.android.documentsui:id/item_root"
                }
            assertEquals("true", target["longClickable"]?.jsonPrimitive?.content)
            val token = target.getValue("token").jsonPrimitive.content
            val action = session.output("ui.long_click", fixture.json("""{"token":"$token"}"""))
            val expectation = """{"condition":"present","text":"1 selected","match":"exact","timeoutMillis":3000}"""
            val wait = session.output("ui.wait", fixture.json(expectation))
            val evidence =
                JSONObject()
                    .put("before", JSONObject(snapshot.toString()))
                    .put("action", JSONObject(action.toString()))
                    .put("after", JSONObject(wait.toString()))
            File(app.filesDir, "mobile-long-press-evidence/semantic.json").writeText(evidence.toString(2))
            assertEquals("DISPATCHED", action.getValue("status").jsonPrimitive.content)
            assertEquals("FOUND", wait.getValue("status").jsonPrimitive.content)
        }
    }

    private fun observeDevice(session: MobileInteractionSession): JsonObject {
        var observed: JsonObject? = null
        fixture.await("read-only device target ready") {
            observed = session.output("ui.device")
            observed?.get("frame") != null
        }
        return checkNotNull(observed)
    }

    @Test fun accessibilityHoldReachesNativeLongClick() {
        require(InstrumentationRegistry.getArguments().getString("helixLongPress") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val report = JSONArray()
        try {
            MobileInteractionSession(app, setOf(fixture.packageName)).use { session ->
                fixture.launch()
                observeDevice(session)
                SystemClock.sleep(1000)
                for (duration in listOf(700, 900, 1300, 1800)) {
                    fixture.change("reset-touch")
                    fixture.await("cleared touch evidence") { fixture.read("touchEvents") == "[]" }
                    val device = observeDevice(session)
                    assertEquals("accessibility", device.getValue("clickMatchBackend").jsonPrimitive.content)
                    val frame = device.getValue("frame").jsonPrimitive.content
                    val result =
                        session.output(
                            "ui.gesture",
                            fixture.json(
                                """{"frame":"$frame","pattern":{"kind":"long_press","center":${fixture.point(
                                    "bx",
                                    "by",
                                )},"durationMillis":$duration}}""",
                            ),
                        )
                    SystemClock.sleep(100)
                    report.put(
                        JSONObject()
                            .put("durationMillis", duration)
                            .put("status", result.getValue("status").jsonPrimitive.content)
                            .put("longPresses", fixture.number("longPresses"))
                            .put("clicks", fixture.number("semanticClicks"))
                            .put("events", JSONArray(fixture.read("touchEvents"))),
                    )
                }
            }
        } finally {
            val directory = File(app.filesDir, "mobile-long-press-evidence").apply { mkdirs() }
            File(directory, "native.json").writeText(report.toString(2))
        }
        assertEquals(4, report.length())
        for (index in 0 until report.length()) {
            val result = report.getJSONObject(index)
            assertEquals("DISPATCHED", result.getString("status"))
            assertTrue(result.toString(), result.getDouble("longPresses") == 1.0 && result.getDouble("clicks") == 0.0)
        }
    }
}

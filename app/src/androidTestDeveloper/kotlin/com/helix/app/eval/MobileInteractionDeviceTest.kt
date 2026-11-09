package com.helix.app.eval

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ToolDispatchOutcome
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MobileInteractionDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val fixture = MobileInteractionFixture()

    @Test fun fixtureRenders() {
        // Do not let instrumentation kill the fresh-install host while its first database creation is in flight.
        assertTrue(
            app.appContainer.storage.turns
                .listActive()
                .isEmpty(),
        )
        fixture.launch()
        assertTrue(fixture.number("ticks") > 0)
    }

    @Test fun privilegedPatternsAndWindowBoundaries() {
        require(InstrumentationRegistry.getArguments().getString("helixMobileCloseout") == "true")
        awaitComputerAdbHelper()
        fixture.launch()
        MobileInteractionSession(app).use { session ->
            val report = exercisePatterns(session)
            save("adb-patterns", report)
            exerciseWindows(session)
            save(
                "adb",
                report
                    .put(
                        "timings",
                        session.timings,
                    ).put("windows", "keyboard,WebView,notification,dialog,rotation passed"),
            )
        }
    }

    @Test fun accessibilityPatternsAndTargetRevalidation() {
        require(InstrumentationRegistry.getArguments().getString("helixMobileCloseout") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        fixture.launch()
        MobileInteractionSession(app).use { session ->
            val device = session.output("ui.device")
            assertEquals("accessibility", device.getValue("clickMatchBackend").jsonPrimitive.content)
            val report = exercisePatterns(session)
            save("accessibility", report.put("timings", session.timings))
            exerciseSemanticLease(session)
        }
    }

    private fun frame(session: MobileInteractionSession): String {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        do {
            val device = session.output("ui.device")
            val token = device.getValue("frame").jsonPrimitive.content
            val capture = session.output("ui.screenshot", fixture.frame(token))
            val status = capture.getValue("status").jsonPrimitive.content
            if (status in setOf("TARGET_CHANGED", "STALE_TOKEN")) {
                // IME/window animations can finish after the observation; reacquire reads only, never replay input.
                println("MOBILE_CAPTURE_REOBSERVE status=$status")
                SystemClock.sleep(50)
                continue
            }
            assertEquals("Screenshot refused: $capture", "SAVED", status)
            assertTrue(
                "Screenshot did not attach pixels: $capture",
                capture["pixelsAttached"]?.jsonPrimitive?.content == "true",
            )
            return capture.getValue("frame").jsonPrimitive.content
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Screenshot window kept changing for 3 seconds; no gesture dispatched")
    }

    private fun gesture(
        session: MobileInteractionSession,
        pattern: String,
    ) {
        val token = frame(session)
        val result = session.output("ui.gesture", fixture.json("""{"frame":"$token","pattern":$pattern}"""))
        assertEquals("DISPATCHED", result.getValue("status").jsonPrimitive.content)
    }

    @Suppress("LongMethod") // Each independently read fixture effect is part of the same authorized journey.
    private fun exercisePatterns(session: MobileInteractionSession): JSONObject {
        val center = fixture.point("cx", "cy")
        // Exercise actual scaling above ViewConfiguration.getScaledMinimumScalingSpan().
        assertTrue("Fixture span must exceed platform threshold", fixture.number("minSpan") < 600)
        gesture(session, """{"kind":"pinch","center":$center,"span":600,"scale":1.5,"durationMillis":500}""")
        val enlarged = fixture.number("scale")
        assertTrue(
            "Scale detector did not enlarge: $enlarged, pointers=${fixture.number("maxPointers")}",
            enlarged in 1.3..1.7,
        )
        gesture(session, """{"kind":"pinch","center":$center,"span":900,"scale":0.7,"durationMillis":500}""")
        val reduced = fixture.number("scale") / enlarged
        assertTrue("Scale detector did not reduce: $reduced", reduced in 0.6..0.8)
        gesture(session, """{"kind":"rotate","center":$center,"span":160,"degrees":90,"durationMillis":500}""")
        val rotation = fixture.number("angle")
        assertEquals(90.0, rotation, 8.0)
        gesture(
            session,
            """{"kind":"swipe","center":$center,"to":{"x":${fixture.number(
                "cx",
            )},"y":${fixture.number("cy") + 150}},"fingers":3,"durationMillis":400}""",
        )
        assertEquals(3.0, fixture.number("maxPointers"), 0.0)
        assertEquals(150.0, fixture.number("threeDy"), 10.0)
        gesture(session, """{"kind":"tap","center":${fixture.point("bx", "by")}}""")
        val expected =
            session.output(
                "ui.wait",
                fixture.json("""{"text":"Applied fixture","condition":"present","timeoutMillis":3000}"""),
            )
        assertEquals("FOUND", expected.getValue("waitStatus").jsonPrimitive.content)
        assertEquals(1.0, fixture.number("semanticClicks"), 0.0)
        val timeout =
            session.output(
                "ui.wait",
                fixture.json("""{"text":"Never appears","condition":"present","timeoutMillis":300}"""),
            )
        assertEquals("TIMED_OUT", timeout.getValue("waitStatus").jsonPrimitive.content)
        assertEquals(1.0, fixture.number("semanticClicks"), 0.0)
        val wx = fixture.number("wx")
        val wy = fixture.number("wy")
        gesture(
            session,
            """{"kind":"swipe","center":{"x":$wx,"y":${wy + 100}},
                "to":{"x":$wx,"y":${wy - 100}},"durationMillis":400}""",
        )
        fixture.await("WebView scroll") { fixture.number("webY") > 20 }
        return JSONObject()
            .put("platformMinimumSpan", fixture.number("minSpan"))
            .put("enlarged", enlarged)
            .put("reducedRatio", reduced)
            .put("rotation", rotation)
            .put("threeFingerDy", fixture.number("threeDy"))
            .put("delayedResult", "FOUND")
            .put("missingResult", "TIMED_OUT without replay")
            .put("webScrollY", fixture.number("webY"))
    }

    private fun exerciseWindows(session: MobileInteractionSession) {
        fixture.change("keyboard")
        fixture.await("IME shown") { fixture.number("ime") == 1.0 }
        val taps = fixture.number("taps")
        gesture(session, """{"kind":"tap","center":${fixture.point("cx", "cy")}}""")
        assertEquals(taps + 1, fixture.number("taps"), 0.0)
        session.output("ui.system", fixture.json("""{"action":"back"}"""))
        fixture.await("IME hidden") { fixture.number("ime") == 0.0 }
        val old = frame(session)
        session.output("ui.system", fixture.json("""{"action":"notifications"}"""))
        // Global actions deliberately retire the prior coordinate lease before the target is inspected.
        assertRejected(session, old, setOf("NO_ACTIVE_SESSION"))
        awaitPackage(session, "com.android.systemui")
        session.output("ui.system", fixture.json("""{"action":"back"}"""))
        awaitPackage(session, fixture.packageName)
        val beforeDialog = frame(session)
        fixture.change("dialog")
        SystemClock.sleep(500)
        assertRejected(session, beforeDialog)
        session.output("ui.system", fixture.json("""{"action":"back"}"""))
        awaitPackage(session, fixture.packageName)
        val beforeRotation = frame(session)
        fixture.change("rotate")
        fixture.await("landscape") {
            fixture.number("orientation").toInt() ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        assertRejected(session, beforeRotation)
    }

    private fun awaitPackage(
        session: MobileInteractionSession,
        name: String,
    ) {
        fixture.await("foreground package $name") {
            session.output("ui.device")["packageName"]?.jsonPrimitive?.content == name
        }
    }

    private fun assertRejected(
        session: MobileInteractionSession,
        token: String,
        statuses: Set<String> = setOf("TARGET_CHANGED", "STALE_TOKEN"),
    ) {
        val before = fixture.number("taps")
        val result =
            session.dispatch(
                "ui.gesture",
                fixture.json("""{"frame":"$token","pattern":{"kind":"tap","center":${fixture.point("cx", "cy")}}}"""),
            )
        assertTrue(
            "Old coordinates were not refused at execution: $result",
            result is ToolDispatchOutcome.ExecutionFailed,
        )
        val failed = result as ToolDispatchOutcome.ExecutionFailed
        assertTrue(
            "Unexpected refusal: $failed",
            failed.sideEffectFree && statuses.any(failed.detail::startsWith),
        )
        assertEquals(before, fixture.number("taps"), 0.0)
    }

    private fun exerciseSemanticLease(session: MobileInteractionSession) {
        fixture.launch()
        frame(session)
        val retained = semanticToken(session)
        val tick = fixture.number("ticks")
        fixture.await("unrelated comment update") { fixture.number("ticks") > tick }
        SystemClock.sleep(100)
        val result = session.output("ui.click", fixture.json("""{"token":"$retained"}"""))
        assertEquals("DISPATCHED", result.getValue("status").jsonPrimitive.content)
        fixture.await("semantic click effect") { fixture.number("semanticClicks") == 1.0 }
        val changed = semanticToken(session)
        fixture.change("label")
        SystemClock.sleep(250)
        assertSemanticRefused(session, changed)
        assertEquals(1.0, fixture.number("semanticClicks"), 0.0)
        fixture.launch()
        frame(session)
        val covered = semanticToken(session)
        fixture.change("cover")
        SystemClock.sleep(250)
        assertSemanticRefused(session, covered)
        assertEquals(0.0, fixture.number("semanticClicks"), 0.0)
        save(
            "semantic",
            JSONObject()
                .put("unrelatedComments", "DISPATCHED and one verified click")
                .put("changedLabel", "STALE_TOKEN")
                .put("cover", "STALE_TOKEN"),
        )
    }

    private fun semanticToken(session: MobileInteractionSession): String {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        var last = ""
        do {
            val result = session.output("ui.snapshot")
            last = result.toString()
            val node =
                result["nodes"]
                    ?.jsonArray
                    ?.map { it.jsonObject }
                    ?.singleOrNull { it["text"]?.jsonPrimitive?.content == "Apply fixture" }
            if (node != null) return node.getValue("token").jsonPrimitive.content
            check(
                result["status"]?.jsonPrimitive?.content in setOf("SUCCESS", "TARGET_CHANGED", "TARGET_UNAVAILABLE"),
            ) {
                "Semantic observation refused: $result"
            }
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Fixture semantic button did not become observable: $last")
    }

    private fun assertSemanticRefused(
        session: MobileInteractionSession,
        token: String,
    ) {
        val result = session.dispatch("ui.click", fixture.json("""{"token":"$token"}"""))
        assertTrue("Expected stale semantic token: $result", result is ToolDispatchOutcome.ExecutionFailed)
        val failure = result as ToolDispatchOutcome.ExecutionFailed
        assertTrue(
            "Unexpected semantic refusal: $failure",
            failure.sideEffectFree && failure.detail.startsWith("STALE_TOKEN"),
        )
    }

    private fun save(
        name: String,
        report: JSONObject,
    ) {
        val dir = File(app.filesDir, "mobile-interaction-evidence").apply { mkdirs() }
        File(dir, "$name.json").writeText(report.toString(2))
    }
}

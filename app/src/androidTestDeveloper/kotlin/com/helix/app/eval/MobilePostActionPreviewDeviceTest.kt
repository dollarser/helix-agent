package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MobilePostActionPreviewDeviceTest {
    @Test fun shortenedScrollObservationCanBeExpandedWithFreshReads() {
        require(InstrumentationRegistry.getArguments().getString("helixPostActionPreview") == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val fixture = MobileInteractionFixture()
        val target = InstrumentationRegistry.getInstrumentation().context.packageName
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val evidence = JSONObject()
        try {
            MobileInteractionSession(app, setOf(target)).use { session ->
                app.startActivity(
                    Intent()
                        .setComponent(ComponentName(target, ScrollBoundaryFixtureActivity::class.java.name))
                        .putExtra("densePreview", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                fixture.await("bottom edge with dense visible content") {
                    session.output("ui.snapshot")["nodes"]?.jsonArray?.any {
                        it.jsonObject["text"]?.jsonPrimitive?.content == "y=3500;calls=0"
                    } == true
                }
                verifyPreview(session, fixture, evidence)
            }
        } finally {
            val directory = File(app.filesDir, "mobile-preview-evidence").apply { mkdirs() }
            File(directory, "preview.json").writeText(evidence.toString(2))
        }
    }

    private fun verifyPreview(
        session: MobileInteractionSession,
        fixture: MobileInteractionFixture,
        evidence: JSONObject,
    ) {
        val scroll =
            session
                .output(
                    "ui.find",
                    fixture.json("""{"contentDescription":"Boundary scroll target","scrollable":true}"""),
                ).getValue("nodes")
                .jsonArray
                .single()
                .jsonObject
        val token = scroll.getValue("token").jsonPrimitive.content
        val result = session.output("ui.scroll", fixture.json("""{"token":"$token","direction":"backward"}"""))
        evidence.put("scroll", JSONObject(result.toString()))
        val preview = result.getValue("observation").jsonObject
        assertEquals(32, preview.getValue("nodes").jsonArray.size)
        assertEquals(32, preview.getValue("returnedNodeCount").jsonPrimitive.int)
        assertTrue(preview.getValue("capturedNodeCount").jsonPrimitive.int > 32)
        assertTrue(
            preview.getValue("truncationReasons").jsonArray.any {
                it.jsonPrimitive.content ==
                    "OUTPUT_LIMIT"
            },
        )
        assertTrue(
            result
                .getValue("nextObservation")
                .jsonPrimitive.content
                .contains("ui.snapshot"),
        )
        val wider = session.output("ui.snapshot")
        evidence.put("wider", JSONObject(wider.toString()))
        assertTrue(wider.getValue("nodes").jsonArray.size > 32)
        assertEquals(
            wider.getValue("nodes").jsonArray.size,
            wider.getValue("returnedNodeCount").jsonPrimitive.int,
        )
        val found = session.output("ui.find", fixture.json("""{"contentDescription":"Preview label 39"}"""))
        evidence.put("target", JSONObject(found.toString()))
        assertEquals(1, found.getValue("nodes").jsonArray.size)
    }
}

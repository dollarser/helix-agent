package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Opt-in native accessibility observations through the production Dispatcher; never dispatches clicks. */
class MobileWaitScopeDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val fixture = MobileInteractionFixture()
    private val target = InstrumentationRegistry.getInstrumentation().context.packageName

    @Test fun limitedDuplicateTargetsDoNotSuggestUniqueClick() =
        exercise("duplicate-targets") { session, evidence ->
            val selector = fixture.json("""{"text":"Wait duplicate target"}""")
            val before = session.output("ui.find", selector)
            evidence.put("before", JSONObject(before.toString()))
            val nodes = before.getValue("nodes").jsonArray
            assertEquals(2, nodes.size)
            assertTrue(
                nodes.all {
                    it.jsonObject
                        .getValue("clickable")
                        .jsonPrimitive.boolean
                },
            )
            val targets =
                nodes.map {
                    it.jsonObject
                        .getValue("clickTargetToken")
                        .jsonPrimitive.content
                }
            assertTrue(targets.all { it.isNotBlank() })
            assertEquals(2, targets.distinct().size)
            val output =
                session.output(
                    "ui.wait",
                    fixture.json("""{"text":"Wait duplicate target","maxResults":1,"timeoutMillis":2000}"""),
                )
            evidence.put("wait", JSONObject(output.toString()))
            assertEquals("FOUND", output.getValue("waitStatus").jsonPrimitive.content)
            assertEquals(2, output.getValue("matchCount").jsonPrimitive.int)
            assertEquals(1, output.getValue("returnedNodeCount").jsonPrimitive.int)
            assertEquals(1, output.getValue("nodes").jsonArray.size)
            assertEquals(1, output.getValue("maxResults").jsonPrimitive.int)
            assertTrue(output.getValue("ambiguous").jsonPrimitive.boolean)
            assertLimitedCompleteCapture(output)
            assertFalse(output.containsKey("suggestedClickToken"))
            assertWaitSchema(output)
            val after = session.output("ui.find", selector)
            evidence.put("after", JSONObject(after.toString()))
            // Each native click listener changes its label, so both unchanged labels also witness no click.
            assertEquals(2, after.getValue("matchCount").jsonPrimitive.int)
            assertEquals(2, after.getValue("nodes").jsonArray.size)
        }

    @Test fun defaultLimitReportsCompleteCaptureAndLimitedQuery() =
        exercise("default-limit") { session, evidence ->
            val output =
                session.output(
                    "ui.wait",
                    fixture.json("""{"condition":"stable","stableMillis":200,"timeoutMillis":5000}"""),
                )
            evidence.put("wait", JSONObject(output.toString()))
            assertEquals("STABLE", output.getValue("waitStatus").jsonPrimitive.content)
            val captured = output.getValue("capturedNodeCount").jsonPrimitive.int
            assertTrue(captured > 20)
            assertEquals(captured, output.getValue("matchCount").jsonPrimitive.int)
            assertEquals(20, output.getValue("returnedNodeCount").jsonPrimitive.int)
            assertEquals(20, output.getValue("nodes").jsonArray.size)
            assertEquals(20, output.getValue("maxResults").jsonPrimitive.int)
            assertLimitedCompleteCapture(output)
            assertFalse(output.containsKey("suggestedClickToken"))
            assertWaitSchema(output)
        }

    private fun assertLimitedCompleteCapture(output: JsonObject) {
        assertFalse(output.getValue("captureTruncated").jsonPrimitive.boolean)
        assertTrue(output.getValue("queryTruncated").jsonPrimitive.boolean)
        assertTrue(output.getValue("truncated").jsonPrimitive.boolean)
    }

    private fun assertWaitSchema(output: JsonObject) {
        val descriptor = requireNotNull(app.appContainer.toolPipeline.resolveLatest("ui.wait"))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, output))
    }

    private fun exercise(
        name: String,
        verify: (MobileInteractionSession, JSONObject) -> Unit,
    ) {
        require(InstrumentationRegistry.getArguments().getString("helixWaitScope") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val evidence = JSONObject()
        try {
            MobileInteractionSession(app, setOf(target)).use { session ->
                app.startActivity(
                    Intent()
                        .setComponent(ComponentName(target, ScrollBoundaryFixtureActivity::class.java.name))
                        .putExtra("densePreview", true)
                        .putExtra("waitScopeTargets", true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                fixture.await("dense native fixture ready") {
                    session.output("ui.snapshot")["nodes"]?.jsonArray?.any {
                        it.jsonObject["contentDescription"]?.jsonPrimitive?.content == "Preview label 39"
                    } == true
                }
                verify(session, evidence)
                evidence.put("timings", session.timings)
            }
        } finally {
            val directory = File(app.filesDir, "mobile-wait-scope-evidence").apply { mkdirs() }
            File(directory, "$name.json").writeText(evidence.toString(2))
        }
    }
}

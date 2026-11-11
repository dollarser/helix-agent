package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.model.ToolDispatchOutcome
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MobileScrollBoundaryDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val fixture = MobileInteractionFixture()
    private val targetPackage = InstrumentationRegistry.getInstrumentation().context.packageName

    @Test fun unsupportedDirectionIsNotDispatchedAndValidBackwardStillScrolls() = exercise(false)

    @Test fun negativeAcknowledgementAfterRealChangeRemainsUncertain() = exercise(true)

    private fun exercise(negativeAck: Boolean) {
        require(InstrumentationRegistry.getArguments().getString("helixScrollBoundary") == "true")
        val center = AutomationPermissionCenter(app)
        fixture.await("accessibility connection") { center.serviceState() == AutomationServiceState.CONNECTED }
        val report = JSONObject()
        try {
            MobileInteractionSession(app, setOf(targetPackage)).use { session ->
                launch(negativeAck)
                val before = awaitBottom(session)
                report.put("before", JSONObject(before.toString()))
                val initial = target(session)
                assertEquals(listOf("backward", "up"), directions(initial).sorted())
                if (!negativeAck) {
                    val refused =
                        session.dispatch(
                            "ui.scroll",
                            args(initial, "forward"),
                        ) as ToolDispatchOutcome.ExecutionFailed
                    assertTrue(refused.detail.startsWith("ACTION_NOT_SUPPORTED:"))
                    assertTrue(refused.sideEffectFree)
                    assertFalse(refused.requiresReview)
                    report.put(
                        "refused",
                        JSONObject()
                            .put("detail", refused.detail)
                            .put("sideEffectFree", refused.sideEffectFree)
                            .put("requiresReview", refused.requiresReview),
                    )
                    assertEquals("y=3500;calls=0", receipt(session.output("ui.snapshot")))
                }
                val result = session.dispatch("ui.scroll", args(target(session), "backward"))
                if (negativeAck) {
                    val uncertain = result as ToolDispatchOutcome.ExecutionFailed
                    assertEquals("ACTION_FAILED", uncertain.detail)
                    assertFalse(uncertain.sideEffectFree)
                    assertTrue(uncertain.requiresReview)
                    report.put(
                        "uncertain",
                        JSONObject()
                            .put("sideEffectFree", uncertain.sideEffectFree)
                            .put("requiresReview", uncertain.requiresReview),
                    )
                } else {
                    assertTrue(result is ToolDispatchOutcome.Succeeded)
                }
                SystemClock.sleep(500)
                val after = session.output("ui.snapshot")
                report.put("after", JSONObject(after.toString()))
                val value = receipt(after)
                assertTrue(value.endsWith(";calls=1"))
                assertTrue(value.substringAfter("y=").substringBefore(';').toInt() < 3500)
                if (negativeAck) assertEquals("y=0;calls=1", value)
            }
        } finally {
            val directory = File(app.filesDir, "mobile-scroll-evidence").apply { mkdirs() }
            File(directory, if (negativeAck) "negative-ack.json" else "boundary.json").writeText(report.toString(2))
        }
    }

    private fun awaitBottom(session: MobileInteractionSession): JsonObject {
        var before = JsonObject(emptyMap())
        fixture.await("native bottom edge") {
            before = session.output("ui.snapshot")
            before["nodes"]?.jsonArray?.any {
                it.jsonObject["text"]?.jsonPrimitive?.content == "y=3500;calls=0"
            } == true
        }
        return before
    }

    private fun launch(negativeAck: Boolean) {
        app.startActivity(
            Intent()
                .setComponent(ComponentName(targetPackage, ScrollBoundaryFixtureActivity::class.java.name))
                .putExtra("negativeAck", negativeAck)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        SystemClock.sleep(1000)
    }

    private fun target(session: MobileInteractionSession): JsonObject =
        session
            .output("ui.find", fixture.json("""{"contentDescription":"Boundary scroll target","scrollable":true}"""))
            .getValue("nodes")
            .jsonArray
            .single()
            .jsonObject

    private fun directions(node: JsonObject) =
        node.getValue("scrollDirections").jsonArray.map { it.jsonPrimitive.content }

    private fun args(
        node: JsonObject,
        direction: String,
    ) = fixture.json("""{"token":"${node.getValue("token").jsonPrimitive.content}","direction":"$direction"}""")

    private fun receipt(snapshot: JsonObject) =
        snapshot
            .getValue("nodes")
            .jsonArray
            .map { it.jsonObject }
            .single { it["contentDescription"]?.jsonPrimitive?.content == "Scroll receipt" }
            .getValue("text")
            .jsonPrimitive.content
}

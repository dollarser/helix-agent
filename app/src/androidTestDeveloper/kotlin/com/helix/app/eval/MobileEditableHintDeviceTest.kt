package com.helix.app.eval

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.AutomationServiceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MobileEditableHintDeviceTest {
    @Suppress("LongMethod") // One clear/restore journey compares platform semantics with an independent native value.
    @Test
    fun clearingNativeFieldExposesEmptyValueEvenWhenPlatformTextStillShowsHint() {
        require(InstrumentationRegistry.getArguments().getString("helixEditableHint") == "true")
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
                        .setComponent(ComponentName(target, EditableHintFixtureActivity::class.java.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
                fixture.await("editable fixture after its entry animation") {
                    field(session, fixture)
                        ?.get("bounds")
                        ?.jsonObject
                        ?.get("left")
                        ?.jsonPrimitive
                        ?.content == "0"
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val before = requireNotNull(field(session, fixture))
                evidence.put("before", JSONObject(before.toString()))
                assertEquals(".md", before.getValue("editableValue").jsonPrimitive.content)
                assertEquals("false", before.getValue("isShowingHintText").jsonPrimitive.content)
                val token = before.getValue("token").jsonPrimitive.content
                val action = session.output("ui.set_text", fixture.json("""{"token":"$token","text":""}"""))
                evidence.put("action", JSONObject(action.toString()))
                val after = requireNotNull(field(session, fixture))
                evidence.put("after", JSONObject(after.toString()))
                assertEquals("", after.getValue("editableValue").jsonPrimitive.content)
                assertEquals("", after.getValue("text").jsonPrimitive.content)
                assertEquals(".md", after.getValue("hintText").jsonPrimitive.content)
                assertEquals("true", after.getValue("isShowingHintText").jsonPrimitive.content)
                val actual = session.output("ui.find", fixture.json("""{"text":"length=0;value="}"""))
                evidence.put("nativeValueReceipt", JSONObject(actual.toString()))
                assertEquals(1, actual.getValue("nodes").jsonArray.size)
                val preview =
                    action
                        .getValue(
                            "observation",
                        ).jsonObject
                        .getValue("nodes")
                        .jsonArray
                        .map { it.jsonObject }
                assertTrue(
                    preview.any {
                        it["editableValue"]?.jsonPrimitive?.content == "" &&
                            it["isShowingHintText"]?.jsonPrimitive?.content == "true"
                    },
                )
                val fresh = requireNotNull(field(session, fixture)).getValue("token").jsonPrimitive.content
                session.output("ui.set_text", fixture.json("""{"token":"$fresh","text":".md"}"""))
                val restored = requireNotNull(field(session, fixture))
                evidence.put("restored", JSONObject(restored.toString()))
                assertEquals(".md", restored.getValue("editableValue").jsonPrimitive.content)
                assertEquals("false", restored.getValue("isShowingHintText").jsonPrimitive.content)
            }
        } finally {
            val directory = File(app.filesDir, "mobile-hint-evidence").apply { mkdirs() }
            File(directory, "hint.json").writeText(evidence.toString(2))
        }
    }

    private fun field(
        session: MobileInteractionSession,
        fixture: MobileInteractionFixture,
    ): JsonObject? =
        session
            .output(
                "ui.find",
                fixture.json("""{"contentDescription":"Fixture extension","editable":true}"""),
            )["nodes"]
            ?.jsonArray
            ?.singleOrNull()
            ?.jsonObject
}

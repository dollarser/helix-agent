package com.helix.tools.automation

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.VisualArtifact
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.PublishedToolImage
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationDeviceToolsTest {
    private val port = FakeDevicePort()
    private val actions = FakeSystemPort()
    private val visual = VisualArtifact("artifact", "a".repeat(64), "image/png", 1, 1, 1)
    private var publishedCall: ExecutableToolCall? = null
    private val tools =
        AutomationDeviceTools(
            port,
            actions,
            ToolImagePublication { call, _ ->
                publishedCall = call
                PublishedToolImage("scope:workspace:output/phone.png", "a".repeat(64), 1, visual)
            },
            ToolOrigin.PluginOrigin("mobile-use", "0.2.0", "mobile-use"),
        )

    @Test fun newPrimitivesRetainCapabilityAndEffectClassification() {
        val descriptors = tools.descriptors()
        assertEquals(6, descriptors.size)
        assertTrue(descriptors.all { it.requiredCapabilities == setOf(Capability.ACCESSIBILITY_AUTOMATION) })
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, descriptor("ui.gesture").operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, descriptor("ui.screenshot").operationClass)
        assertEquals(ToolOperationClass.READ_ONLY, descriptor("ui.apps").operationClass)
        assertTrue(
            ToolSchemaValidator.validate(
                descriptor("ui.device").inputSchema,
                json("""{"allApplications":true}"""),
            ) is ToolSchemaValidation.Invalid,
        )
    }

    @Test fun deviceAndAppOutputsMatchTheirDeclaredSchemas() {
        for (name in listOf("ui.device", "ui.apps")) {
            val completed = execute(name, "{}") as ToolExecutorResult.Completed
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor(name).outputSchema, completed.output),
            )
        }
        assertEquals(1, port.observations)
    }

    @Test fun screenshotPublishesThroughOriginalCallAndVisionSidecar() {
        val call = call("ui.screenshot", json("""{"frame":"frame"}"""))
        val result = tools.executor("ui.screenshot").execute(call) as ToolExecutorResult.Completed
        assertSame(call, publishedCall)
        assertSame(visual, result.visualArtifact)
        assertEquals(
            "1",
            result.output.jsonObject
                .getValue("imageWidth")
                .toString(),
        )
        assertEquals(
            "1",
            result.output.jsonObject
                .getValue("imageHeight")
                .toString(),
        )
        assertEquals(
            "1080",
            result.output.jsonObject
                .getValue("width")
                .toString(),
        )
        assertEquals(
            "2400",
            result.output.jsonObject
                .getValue("height")
                .toString(),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui.screenshot").outputSchema, result.output),
        )
        assertFalse(result.output.toString().contains("base64"))
    }

    @Test fun unsupportedScreenshotNeverPublishesAnImageOrFakeArtifact() {
        port.capture = AutomationScreenshot("PLATFORM_SCREENSHOT_ERROR_6")
        val result = execute("ui.screenshot", """{"frame":"frame"}""") as ToolExecutorResult.Completed
        assertEquals(null, publishedCall)
        assertEquals(null, result.visualArtifact)
        assertFalse(result.output.jsonObject.containsKey("reference"))
    }

    @Test fun gesturesArePassedAsOnePhysicalBatchAndUnknownEffectsAreNotReplayable() {
        val args = """{"frame":"frame","strokes":[
            {"points":[{"x":100,"y":200},{"x":200,"y":500}],"durationMillis":300}]}"""
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui.gesture").inputSchema, json(args)),
        )
        execute("ui.gesture", args)
        assertEquals(1, port.strokes.size)
        assertEquals(
            2,
            port.strokes
                .single()
                .points.size,
        )
        port.result = AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        val failed = execute("ui.gesture", args) as ToolExecutorResult.Failed
        assertFalse(failed.sideEffectFree)
        assertTrue(failed.requiresReview)
    }

    @Test fun systemActionsUseExistingPortAndInvalidInputsDoNotAct() {
        execute("ui.system", """{"action":"recents"}""")
        assertEquals(listOf(AutomationGlobalAction.RECENTS), actions.performed)
        val bad = execute("ui.system", """{"action":"root"}""") as ToolExecutorResult.Failed
        assertTrue(bad.sideEffectFree)
        assertEquals(1, actions.performed.size)
    }

    @Test fun expiredCallNeverReachesTheDevicePort() {
        val expired = call("ui.device", json("{}")).copy(deadline = Instant.EPOCH)
        val result = tools.executor("ui.device").execute(expired) as ToolExecutorResult.TimedOutWithEffectTruth
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertEquals(0, port.observations)
    }

    private fun descriptor(name: String) = tools.descriptors().single { it.name.value == name }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun execute(
        name: String,
        args: String,
    ) = tools.executor(name).execute(call(name, json(args)))

    private fun call(
        name: String,
        args: JsonObject,
    ) = ExecutableToolCall(
        "call",
        name,
        "1",
        args,
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(5),
        NoCancellation,
        "session",
        "turn",
    )
}

private class FakeDevicePort : AutomationDevicePort {
    var observations = 0
    var result = AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    var strokes = emptyList<AutomationStroke>()
    var capture = AutomationScreenshot("SAVED", byteArrayOf(1), 1080, 2400, AutomationNodeBounds(0, 0, 1080, 2400))

    override fun observe(): AutomationDeviceObservation {
        observations++
        return AutomationDeviceObservation(
            "READY",
            AutomationFrame(
                "frame",
                "grant",
                AutomationDisplayTarget(
                    "com.example.app",
                    3,
                    0,
                    1080,
                    2400,
                    0,
                    AutomationNodeBounds(0, 0, 1080, 2400),
                ),
            ),
            setOf(AutomationGlobalAction.RECENTS),
            true,
            true,
            true,
        )
    }

    override fun apps() = AutomationAppListing("LISTED", listOf(AutomationApp("com.example.app", "App")))

    override fun launch(
        packageName: String,
        call: ExecutableToolCall,
    ) = result

    override fun gesture(
        frame: String,
        strokes: List<AutomationStroke>,
        call: ExecutableToolCall,
    ): AutomationActionResult {
        this.strokes = strokes
        return result
    }

    override fun screenshot(
        frame: String,
        call: ExecutableToolCall,
    ) = capture
}

private class FakeSystemPort : AutomationToolPort {
    val performed = mutableListOf<AutomationGlobalAction>()

    override fun snapshot() = AutomationSnapshotResult(AutomationSnapshotStatus.UNSUPPORTED_UI)

    override fun nodeAction(request: AutomationNodeActionRequest) =
        AutomationActionResult(AutomationActionStatus.SUCCEEDED)

    override fun globalAction(action: AutomationGlobalAction): AutomationActionResult {
        performed += action
        return AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    }
}

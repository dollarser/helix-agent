package com.helix.extensions.mobileuse.tools

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.VisualArtifact
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationApp
import com.helix.extensions.mobileuse.automation.AutomationAppListing
import com.helix.extensions.mobileuse.automation.AutomationDeviceObservation
import com.helix.extensions.mobileuse.automation.AutomationDevicePort
import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget
import com.helix.extensions.mobileuse.automation.AutomationFrame
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationScreenshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.PublishedToolImage
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationDeviceToolsTest {
    @Test fun samplingParametersShareExactIntegerSemanticsWithTheirSchema() {
        val args = """{"frame":"frame","count":1e0,"intervalMillis":1e3}"""
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_screenshot").inputSchema, json(args)),
        )
        val result = execute("ui_screenshot", args) as ToolExecutorResult.Completed
        assertEquals(
            1,
            result.output.jsonObject
                .getValue("samples")
                .jsonArray.size,
        )
        assertEquals(
            "single",
            result.output.jsonObject
                .getValue("captureKind")
                .jsonPrimitive.content,
        )
        for (bad in listOf("0", "5", "1.5", "\"2\"")) {
            assertTrue(
                ToolSchemaValidator.validate(
                    descriptor("ui_screenshot").inputSchema,
                    json("""{"frame":"frame","count":$bad}"""),
                ) is ToolSchemaValidation.Invalid,
            )
        }
    }

    private val port = FakeDevicePort()
    private val actions = FakeSystemPort()
    private val visual = VisualArtifact("artifact", "a".repeat(64), "image/png", 1, 1, 1)
    private var publishedCall: ExecutableToolCall? = null
    private val tools = toolsFor("com.helix.agent.developer")

    @Test fun gestureDiscoveryDeclaresItsFrameAndCapabilityTools() {
        val descriptors = tools.descriptors().associateBy { it.name.value }

        fun companions(name: String) =
            descriptors
                .getValue(name)
                .discoveryCompanions
                .map { it.value }
                .toSet()
        assertEquals(setOf("ui_screenshot", "ui_device", "ui_wait", "ui_long_click"), companions("ui_gesture"))
        assertEquals(setOf("ui_device"), companions("ui_screenshot"))
        assertTrue(companions("ui_device").isEmpty())
    }

    private fun toolsFor(hostPackage: String) =
        AutomationDeviceTools(
            port,
            actions,
            ToolImagePublication { call, _, _ ->
                publishedCall = call
                PublishedToolImage("scope:workspace:output/phone.png", "a".repeat(64), 1, visual)
            },
            ToolOrigin.PluginOrigin("mobile-use", "0.2.0", "mobile-use"),
            originatingHelixPackage = hostPackage,
        )

    @Test fun newPrimitivesRetainCapabilityAndEffectClassification() {
        val descriptors = tools.descriptors()
        assertEquals(6, descriptors.size)
        descriptors.forEach {
            val expected = Capability.MOBILE_USE
            assertEquals(setOf(expected), it.requiredCapabilities)
        }
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, descriptor("ui_gesture").operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, descriptor("ui_screenshot").operationClass)
        assertEquals(ToolOperationClass.READ_ONLY, descriptor("ui_apps").operationClass)
        assertTrue(
            ToolSchemaValidator.validate(
                descriptor("ui_device").inputSchema,
                json("""{"allApplications":true}"""),
            ) is ToolSchemaValidation.Invalid,
        )
    }

    @Test fun deviceAndAppOutputsMatchTheirDeclaredSchemas() {
        for (name in listOf("ui_device", "ui_apps")) {
            val completed = execute(name, "{}") as ToolExecutorResult.Completed
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor(name).outputSchema, completed.output),
            )
        }
        assertEquals(1, port.observations)
    }

    @Test fun dialogWindowDoesNotChangeAbsoluteDisplayCoordinates() {
        port.windowBounds = AutomationNodeBounds(70, 1016, 1010, 1458)
        val device = (execute("ui_device", "{}") as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(json("""{"left":0,"top":0,"right":1080,"bottom":2400}"""), device["screenBounds"])
        assertEquals(json("""{"left":70,"top":1016,"right":1010,"bottom":1458}"""), device["windowBounds"])
        assertEquals(1, descriptor("ui_device").version.value)
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_device").outputSchema, device),
        )
        execute(
            "ui_gesture",
            """{"frame":"frame","strokes":[
            {"points":[{"x":885,"y":1380}],"durationMillis":100}]}""",
        )
        assertEquals(
            885f,
            port.strokes
                .single()
                .points
                .single()
                .x,
        )
        assertEquals(
            1380f,
            port.strokes
                .single()
                .points
                .single()
                .y,
        )
    }

    @Test fun croppedScreenshotRetainsItsOwnMappingRatherThanWindowOrDisplayBounds() {
        port.windowBounds = AutomationNodeBounds(70, 1016, 1010, 1458)
        port.capture = AutomationScreenshot("SAVED", byteArrayOf(1), 940, 442, port.windowBounds)
        val image =
            (
                execute(
                    "ui_screenshot",
                    """{"frame":"frame"}""",
                ) as ToolExecutorResult.Completed
            ).output.jsonObject
        assertEquals(json("""{"left":70,"top":1016,"right":1010,"bottom":1458}"""), image["screenBounds"])
        assertEquals("940", image.getValue("width").toString())
        assertEquals("1", image.getValue("imageWidth").toString())
        assertFalse(image.containsKey("windowBounds"))
    }

    @Test fun appNameQueryFindsVisiblePackagesWithoutGuessingOrChangingPresenceEvidence() {
        port.catalog =
            AutomationAppListing(
                "LISTED",
                listOf(
                    AutomationApp("com.mmbox.xbrowser", "X浏览器"),
                    AutomationApp("com.example.other", "Other"),
                ),
            )
        listOf("x浏览器", "MMBOX").forEach { query ->
            val output =
                (
                    execute(
                        "ui_apps",
                        """{"query":"$query"}""",
                    ) as ToolExecutorResult.Completed
                ).output.jsonObject
            assertEquals("LISTED", output.getValue("status").jsonPrimitive.content)
            assertEquals(query, output.getValue("query").jsonPrimitive.content)
            assertEquals(
                "com.mmbox.xbrowser",
                output
                    .getValue("apps")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("packageName")
                    .jsonPrimitive.content,
            )
            assertEquals(null, port.queriedPackage)
            assertFalse(output.containsKey("packageName"))
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor("ui_apps").outputSchema, output),
            )
        }
    }

    @Test fun appQueryKeepsAmbiguityAndTruncationInsteadOfInventingAbsence() {
        port.catalog =
            AutomationAppListing(
                "LISTED",
                listOf(
                    AutomationApp("com.example.first", "Browser"),
                    AutomationApp("com.example.second", "Browser"),
                ),
                truncated = true,
            )
        val found = (execute("ui_apps", """{"query":"browser"}""") as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(2, found.getValue("apps").jsonArray.size)
        val missing = (execute("ui_apps", """{"query":"unlisted"}""") as ToolExecutorResult.Completed).output.jsonObject
        assertTrue(missing.getValue("apps").jsonArray.isEmpty())
        assertEquals("LISTED", missing.getValue("status").jsonPrimitive.content)
        assertEquals(true, missing.getValue("truncated").jsonPrimitive.boolean)
    }

    @Test fun invalidAppQueryStopsBeforeCallingThePackageCatalog() {
        listOf("""{"query":"   "}""", """{"query":"Browser","packageName":"com.example.app"}""").forEach {
            val result = execute("ui_apps", it) as ToolExecutorResult.Failed
            assertTrue(result.sideEffectFree)
        }
        assertEquals(0, port.catalogCalls)
        listOf("""{"query":12}""", """{"query":"${"x".repeat(257)}"}""").forEach {
            assertTrue(
                ToolSchemaValidator.validate(
                    descriptor("ui_apps").inputSchema,
                    json(it),
                ) is ToolSchemaValidation.Invalid,
            )
        }
    }

    @Test fun packageQueryRetainsExactTargetAndAbsenceFact() {
        val result = execute("ui_apps", """{"packageName":"com.example.missing"}""") as ToolExecutorResult.Completed
        assertEquals("com.example.missing", port.queriedPackage)
        assertEquals(
            "\"NOT_INSTALLED\"",
            result.output.jsonObject
                .getValue("status")
                .toString(),
        )
        assertEquals(
            "\"com.example.missing\"",
            result.output.jsonObject
                .getValue("packageName")
                .toString(),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_apps").outputSchema, result.output),
        )
        assertEquals(1, descriptor("ui_apps").version.value)
    }

    @Test fun originIdentifiesEachHostIndependentlyOfAppLabelsForegroundAndQuery() {
        for (host in listOf("com.helix.agent", "com.helix.agent.developer")) {
            val hosted = toolsFor(host)
            for ((name, args) in listOf(
                "ui_device" to "{}",
                "ui_apps" to "{}",
                "ui_apps" to
                    """{"packageName":"com.example.missing"}""",
            )) {
                val output =
                    (hosted.executor(name).execute(call(name, json(args))) as ToolExecutorResult.Completed)
                        .output.jsonObject
                assertEquals(host, output.getValue("originatingHelixPackage").jsonPrimitive.content)
                assertEquals(
                    ToolSchemaValidation.Valid,
                    ToolSchemaValidator.validate(
                        hosted.descriptors().single { it.name.value == name }.outputSchema,
                        output,
                    ),
                )
                if (name == "ui_device") {
                    assertEquals("com.example.app", output.getValue("packageName").jsonPrimitive.content)
                }
                assertTrue(
                    ToolSchemaValidator.validate(
                        hosted.descriptors().single { it.name.value == name }.inputSchema,
                        json("""{"originatingHelixPackage":"spoofed.host"}"""),
                    ) is ToolSchemaValidation.Invalid,
                )
            }
        }
    }

    @Test fun unavailableObservationStillReportsHostWithoutInventingForegroundOrFrame() {
        port.frameAvailable = false
        val output = (execute("ui_device", "{}") as ToolExecutorResult.Completed).output.jsonObject
        assertEquals("com.helix.agent.developer", output.getValue("originatingHelixPackage").jsonPrimitive.content)
        assertEquals("UNAVAILABLE", output.getValue("status").jsonPrimitive.content)
        assertFalse(output.containsKey("frame"))
        assertFalse(output.containsKey("packageName"))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_device").outputSchema, output),
        )
    }

    @Test fun screenshotPublishesThroughOriginalCallAndVisionSidecar() {
        port.capture = port.capture.copy(frame = "capture-bound-frame")
        val call = call("ui_screenshot", json("""{"frame":"frame"}"""))
        val result = tools.executor("ui_screenshot").execute(call) as ToolExecutorResult.Completed
        assertSame(call, publishedCall)
        assertSame(visual, result.visualArtifact)
        assertEquals(
            "capture-bound-frame",
            result.output.jsonObject
                .getValue("frame")
                .toString()
                .trim('"'),
        )
        assertTrue(
            result.output.jsonObject
                .getValue("actionHint")
                .toString()
                .contains("ui_gesture"),
        )
        assertEquals(1, descriptor("ui_screenshot").version.value)
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
            ToolSchemaValidator.validate(descriptor("ui_screenshot").outputSchema, result.output),
        )
        assertFalse(result.output.toString().contains("base64"))
        assertTrue(result.output.toString().contains("pixelsAttached"))
    }

    @Test fun unsupportedScreenshotNeverPublishesAnImageOrFakeArtifact() {
        port.capture = AutomationScreenshot("PLATFORM_SCREENSHOT_ERROR_6")
        val result = execute("ui_screenshot", """{"frame":"frame"}""") as ToolExecutorResult.Completed
        assertEquals(null, publishedCall)
        assertEquals(null, result.visualArtifact)
        assertFalse(result.output.jsonObject.containsKey("reference"))
    }

    @Test fun failedCapturePreservesStatusAndExplainsOpaqueFrameRecoveryWithoutPublishing() {
        for (status in listOf("NO_ACTIVE_SESSION", "STALE_FRAME", "TARGET_CHANGED", "SCOPE_DENIED")) {
            port.capture = AutomationScreenshot(status)
            val result = execute("ui_screenshot", """{"frame":"page"}""") as ToolExecutorResult.Completed
            assertEquals(
                status,
                result.output.jsonObject
                    .getValue("status")
                    .jsonPrimitive.content,
            )
            assertTrue(
                result.output.jsonObject
                    .getValue("actionHint")
                    .jsonPrimitive.content
                    .contains("ui_device"),
            )
            assertEquals(null, publishedCall)
            assertEquals(null, result.visualArtifact)
            assertFalse(result.output.jsonObject.containsKey("reference"))
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor("ui_screenshot").outputSchema, result.output),
            )
        }
        assertEquals(0, port.observations)
    }

    @Test fun integralGestureTimingsPreserveExactRequestedDurations() {
        for (token in listOf("300", "300.0", "3e2", "3000e-1")) {
            val args = """{"frame":"frame","strokes":[
                {"points":[{"x":100,"y":200}],"startMillis":1e2,"durationMillis":$token}]}"""
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor("ui_gesture").inputSchema, json(args)),
            )
            assertTrue(execute("ui_gesture", args) is ToolExecutorResult.Completed)
            assertEquals(100L, port.strokes.single().startMillis)
            assertEquals(300L, port.strokes.single().durationMillis)
        }
    }

    @Test fun gesturesArePassedAsOnePhysicalBatchAndUnknownEffectsAreNotReplayable() {
        val args = """{"frame":"frame","strokes":[
            {"points":[{"x":100,"y":200},{"x":200,"y":500}],"durationMillis":300}]}"""
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_gesture").inputSchema, json(args)),
        )
        val completed = execute("ui_gesture", args) as ToolExecutorResult.Completed
        assertEquals(
            "DISPATCHED",
            completed.output.jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertEquals(
            "UNVERIFIED",
            completed.output.jsonObject
                .getValue("effectStatus")
                .jsonPrimitive.content,
        )
        assertEquals(1, port.strokes.size)
        assertTrue(
            completed.output.jsonObject
                .getValue("actionHint")
                .toString()
                .contains("observe"),
        )
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(descriptor("ui_gesture").outputSchema, completed.output),
        )
        assertEquals(
            2,
            port.strokes
                .single()
                .points.size,
        )
        port.result = AutomationActionResult(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN)
        val failed = execute("ui_gesture", args) as ToolExecutorResult.Failed
        assertFalse(failed.sideEffectFree)
        assertTrue(failed.requiresReview)
    }

    @Test fun systemActionsUseExistingPortAndInvalidInputsDoNotAct() {
        execute("ui_system", """{"action":"recents"}""")
        assertEquals(listOf(AutomationGlobalAction.RECENTS), actions.performed)
        val bad = execute("ui_system", """{"action":"root"}""") as ToolExecutorResult.Failed
        assertTrue(bad.sideEffectFree)
        assertEquals(1, actions.performed.size)
    }

    @Test fun expiredCallNeverReachesTheDevicePort() {
        val expired = call("ui_device", json("{}")).copy(deadline = Instant.EPOCH)
        val result = tools.executor("ui_device").execute(expired) as ToolExecutorResult.TimedOutWithEffectTruth
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertEquals(0, port.observations)
    }

    @Test fun cancelledGestureNeverDispatchesOrClaimsAnEffect() {
        val cancelled =
            call(
                "ui_gesture",
                json(
                    """{"frame":"frame","strokes":[
                {"points":[{"x":100,"y":200}],"durationMillis":100}]}""",
                ),
            ).copy(
                cancel =
                    object : com.helix.core.model.CancelSignal {
                        override fun isCancelled() = true
                    },
            )
        val result = tools.executor("ui_gesture").execute(cancelled) as ToolExecutorResult.CancelledWithEffectTruth
        assertTrue(result.sideEffectFree)
        assertFalse(result.requiresReview)
        assertTrue(port.strokes.isEmpty())
    }

    private fun descriptor(name: String) = tools.descriptors().single { it.name.value == name }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject

    private fun execute(
        name: String,
        args: String,
    ) = tools.executor(name).execute(call(name, json(args)))

    @Test fun patternsUseTheOriginalFrameAndRejectAmbiguousOrIncompleteInputs() {
        val args = """{"frame":"frame","pattern":
            {"kind":"pinch","center":{"x":500,"y":500},"span":100,"scale":2}}"""
        assertTrue(
            ToolSchemaValidator.validate(
                descriptor("ui_gesture").inputSchema,
                json(args),
            ) is ToolSchemaValidation.Valid,
        )
        tools.executor("ui_gesture").execute(call("ui_gesture", json(args)))
        assertEquals(2, port.strokes.size)
        assertEquals(
            450f,
            port.strokes[0]
                .points
                .first()
                .x,
        )
        assertEquals(
            400f,
            port.strokes[0]
                .points
                .last()
                .x,
        )
        for (bad in listOf(
            """{"frame":"frame"}""",
            """{"frame":"frame","strokes":[],"pattern":{"kind":"tap","center":{"x":1,"y":1}}}""",
            """{"frame":"frame","pattern":{"kind":"swipe","center":{"x":1,"y":1}}}""",
            """{"frame":"frame","pattern":{"kind":"pinch","center":{"x":1,"y":1},"span":20,"scale":0}}""",
            """{"frame":"frame","pattern":{"kind":"tap","center":{"x":1,"y":1},"scale":2}}""",
        )) {
            port.strokes = emptyList()
            assertTrue(tools.executor("ui_gesture").execute(call("ui_gesture", json(bad))) is ToolExecutorResult.Failed)
            assertTrue(port.strokes.isEmpty())
        }
    }

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
    var frameAvailable = true
    var windowBounds = AutomationNodeBounds(0, 0, 1080, 2400)
    var observations = 0
    var result = AutomationActionResult(AutomationActionStatus.SUCCEEDED)
    var strokes = emptyList<AutomationStroke>()
    var capture = AutomationScreenshot("SAVED", byteArrayOf(1), 1080, 2400, AutomationNodeBounds(0, 0, 1080, 2400))

    override fun observe(): AutomationDeviceObservation {
        observations++
        return AutomationDeviceObservation(
            if (frameAvailable) "READY" else "UNAVAILABLE",
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
                    windowBounds,
                ),
            ).takeIf { frameAvailable },
            setOf(AutomationGlobalAction.RECENTS),
            true,
            true,
            true,
        )
    }

    var catalog =
        AutomationAppListing(
            "LISTED",
            listOf(AutomationApp("com.helix.agent", "Helix"), AutomationApp("com.helix.agent.developer", "Helix")),
        )
    var catalogCalls = 0
    var queriedPackage: String? = null

    override fun apps(packageName: String?): AutomationAppListing {
        queriedPackage = packageName
        catalogCalls++
        return if (packageName == null) {
            catalog
        } else {
            AutomationAppListing("NOT_INSTALLED", queriedPackage = packageName)
        }
    }

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

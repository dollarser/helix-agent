package com.helix.tools.automation

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImagePublication
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** Phone primitives share the existing dispatcher and image pipeline, not another task engine. */
@Suppress("TooManyFunctions") // Versioned JSON contracts and their executors remain together.
class AutomationDeviceTools(
    private val port: AutomationDevicePort,
    private val actions: AutomationToolPort,
    private val images: ToolImagePublication,
    private val origin: ToolOrigin,
) {
    fun descriptors(): List<ToolDescriptor> =
        listOf(
            descriptor(
                DEVICE,
                ToolOperationClass.READ_ONLY,
                obj(),
                deviceOutput(),
                "Observe phone display/window and get a frame for screenshot or gesture. " +
                    "A new observation replaces the old frame; semantic node tokens are separate.",
            ),
            descriptor(
                APPS,
                ToolOperationClass.READ_ONLY,
                obj(),
                appsOutput(),
                "List Android-visible launchable apps allowed by the user grant. " +
                    "Not all installed packages; truncated reports incomplete listing.",
            ),
            descriptor(
                LAUNCH,
                ToolOperationClass.EXTERNAL_ACTION,
                obj(mapOf("packageName" to str(255)), "packageName"),
                actionOutput(),
                "Open an authorized app by package name from ui.apps; never grants new targets.",
            ),
            descriptor(
                SYSTEM,
                ToolOperationClass.EXTERNAL_ACTION,
                obj(mapOf("action" to choices(AutomationGlobalAction.entries.map(::actionName))), "action"),
                actionOutput(),
                "Run an action reported by ui.device. Back/home can leave a paused screen. " +
                    "Other system controls need system/whole-phone scope. " +
                    "headset_hook can answer/end a call; lock_screen ends the automation grant.",
            ),
            descriptor(
                GESTURE,
                ToolOperationClass.EXTERNAL_ACTION,
                gestureInput(),
                actionOutput(),
                "Tap/hold/swipe/drag/pinch using strokes in physical screen pixels, not dp. " +
                    "One point taps/holds; several points form a path. " +
                    "Parallel strokes use multiple fingers. " +
                    "Map image pixels via screenBounds. " +
                    "Refresh frame after window/rotation changes. " +
                    "Observe after uncertain outcomes; never blindly replay.",
            ),
            descriptor(
                SCREENSHOT,
                ToolOperationClass.LOCAL_MUTATION,
                obj(mapOf("frame" to str(64)), "frame"),
                screenshotOutput(),
                "Capture a ui.device frame into a PNG artifact and the shared vision pipeline. " +
                    "Whole-display needs API30+, scoped window API34+. Protected surfaces can fail. " +
                    "Pixels require a vision-capable model and existing disclosure. " +
                    "width/height describe the PNG; imageWidth/imageHeight describe attached pixels. " +
                    "screenX=left+x*(right-left)/imageWidth; screenY=top+y*(bottom-top)/imageHeight.",
            ),
        )

    fun executor(name: String): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                beforeDispatch(call)?.let { return it }
                return try {
                    when (name) {
                        DEVICE -> {
                            ToolExecutorResult.Completed(deviceJson(port.observe()))
                        }

                        APPS -> {
                            apps()
                        }

                        LAUNCH -> {
                            port.launch(text(call.args, "packageName"), call).toToolOutcome()
                        }

                        SYSTEM -> {
                            val requested = text(call.args, "action").uppercase(Locale.ROOT)
                            val action = AutomationGlobalAction.valueOf(requested)
                            actions.globalAction(action).toToolOutcome()
                        }

                        GESTURE -> {
                            port.gesture(text(call.args, "frame"), strokes(call.args), call).toToolOutcome()
                        }

                        SCREENSHOT -> {
                            screenshot(call)
                        }

                        else -> {
                            ToolExecutorResult.Failed("AUTOMATION_TOOL_UNKNOWN", sideEffectFree = true)
                        }
                    }
                } catch (_: IllegalArgumentException) {
                    ToolExecutorResult.Failed("AUTOMATION_ARGUMENT_INVALID", sideEffectFree = name != SCREENSHOT)
                }
            }
        }

    private fun beforeDispatch(call: ExecutableToolCall): ToolExecutorResult? =
        when {
            call.cancel.isCancelled() -> {
                ToolExecutorResult.CancelledWithEffectTruth(
                    "AUTOMATION_CANCELLED_BEFORE_DISPATCH",
                    sideEffectFree = true,
                    requiresReview = false,
                )
            }

            !Instant.now().isBefore(call.deadline) -> {
                ToolExecutorResult.TimedOutWithEffectTruth(
                    "AUTOMATION_DEADLINE_BEFORE_DISPATCH",
                    sideEffectFree = true,
                    requiresReview = false,
                )
            }

            else -> {
                null
            }
        }

    private fun apps(): ToolExecutorResult {
        val listing = port.apps()
        val items =
            listing.apps.map { app ->
                buildJsonObject {
                    put("packageName", JsonPrimitive(app.packageName))
                    put("label", JsonPrimitive(app.label))
                }
            }
        return ToolExecutorResult.Completed(
            buildJsonObject {
                put("status", JsonPrimitive(listing.status))
                put("truncated", JsonPrimitive(listing.truncated))
                put("apps", JsonArray(items))
            },
        )
    }

    @Suppress("ReturnCount") // Failed capture and cancelled pre-publication calls do not create artifacts.
    private fun screenshot(call: ExecutableToolCall): ToolExecutorResult {
        val capture = port.screenshot(text(call.args, "frame"), call)
        val bytes =
            capture.png ?: return ToolExecutorResult.Completed(
                buildJsonObject {
                    put("status", JsonPrimitive(capture.status))
                },
            )
        beforeDispatch(call)?.let { return it }
        val published = images.publish(call, bytes)
        val output =
            buildJsonObject {
                put("status", JsonPrimitive("SAVED"))
                put("reference", JsonPrimitive(published.reference))
                put("sha256", JsonPrimitive(published.sha256))
                put("sizeBytes", JsonPrimitive(published.sizeBytes))
                put("width", JsonPrimitive(capture.width))
                put("height", JsonPrimitive(capture.height))
                put("imageWidth", JsonPrimitive(published.visual?.width ?: capture.width))
                put("imageHeight", JsonPrimitive(published.visual?.height ?: capture.height))
                put("pixelsAttached", JsonPrimitive(published.visual != null))
                capture.screenBounds?.let { put("screenBounds", boundsJson(it)) }
                put("note", JsonPrimitive(published.note))
            }
        return ToolExecutorResult.Completed(output, visualArtifact = published.visual)
    }

    private fun strokes(args: JsonObject): List<AutomationStroke> =
        args.getValue("strokes").jsonArray.map { value ->
            val stroke = value.jsonObject
            val points =
                stroke.getValue("points").jsonArray.map { point ->
                    val fields = point.jsonObject
                    AutomationPoint(fields.getValue("x").jsonPrimitive.float, fields.getValue("y").jsonPrimitive.float)
                }
            AutomationStroke(
                points,
                stroke["startMillis"]?.jsonPrimitive?.long ?: 0,
                stroke.getValue("durationMillis").jsonPrimitive.long,
            )
        }

    private fun deviceJson(observation: AutomationDeviceObservation) =
        buildJsonObject {
            put("status", JsonPrimitive(observation.status))
            put("allApplications", JsonPrimitive(observation.allApplications))
            put("screenshotSupported", JsonPrimitive(observation.screenshotSupported))
            put("gestureSupported", JsonPrimitive(observation.gestureSupported))
            put("systemActions", JsonArray(observation.systemActions.map { JsonPrimitive(actionName(it)) }))
            observation.frame?.let { frame ->
                put("frame", JsonPrimitive(frame.token))
                put("packageName", JsonPrimitive(frame.target.packageName))
                put("windowId", JsonPrimitive(frame.target.windowId))
                put("displayId", JsonPrimitive(frame.target.displayId))
                put("width", JsonPrimitive(frame.target.width))
                put("height", JsonPrimitive(frame.target.height))
                put("rotation", JsonPrimitive(frame.target.rotation))
                put("screenBounds", boundsJson(frame.target.bounds))
            }
        }

    private fun actionName(action: AutomationGlobalAction): String = action.name.lowercase(Locale.ROOT)

    private fun descriptor(
        name: String,
        operation: ToolOperationClass,
        input: JsonObject,
        output: JsonObject,
        help: String,
    ) = ToolDescriptor(
        ToolName(name),
        ToolVersion(1),
        help,
        input,
        output,
        operation,
        75.seconds,
        512L * 1024L,
        setOf(Capability.ACCESSIBILITY_AUTOMATION),
        if (operation == ToolOperationClass.READ_ONLY) Idempotency.IDEMPOTENT else Idempotency.NON_IDEMPOTENT,
        ExecutionTargetType.LOCAL_ANDROID,
        origin,
    )

    private fun gestureInput(): JsonObject {
        val point = obj(mapOf("x" to number(), "y" to number()), "x", "y")
        val stroke =
            obj(
                mapOf("points" to array(point, 512), "startMillis" to integer(), "durationMillis" to integer()),
                "points",
                "durationMillis",
            )
        return obj(mapOf("frame" to str(64), "strokes" to array(stroke, 20)), "frame", "strokes")
    }

    private fun deviceOutput() =
        obj(
            mapOf(
                "status" to str(128),
                "frame" to str(64),
                "allApplications" to bool(),
                "screenshotSupported" to bool(),
                "gestureSupported" to bool(),
                "systemActions" to array(str(64), 32),
                "packageName" to str(255),
                "windowId" to integer(),
                "displayId" to integer(),
                "width" to integer(),
                "height" to integer(),
                "rotation" to integer(),
                "screenBounds" to boundsSchema(),
            ),
            "status",
        )

    private fun appsOutput(): JsonObject {
        val entry = obj(mapOf("packageName" to str(255), "label" to str(256)), "packageName", "label")
        return obj(mapOf("truncated" to bool(), "status" to str(128), "apps" to array(entry, 1_000)), "status", "apps")
    }

    private fun screenshotOutput() =
        obj(
            mapOf(
                "status" to str(128),
                "reference" to str(1024),
                "sha256" to str(64),
                "sizeBytes" to integer(),
                "width" to integer(),
                "height" to integer(),
                "imageWidth" to integer(),
                "imageHeight" to integer(),
                "pixelsAttached" to bool(),
                "screenBounds" to boundsSchema(),
                "note" to str(512),
            ),
            "status",
        )

    private fun actionOutput() = obj(mapOf("status" to str(64)), "status")

    private fun boundsSchema() =
        obj(
            mapOf("left" to integer(), "top" to integer(), "right" to integer(), "bottom" to integer()),
        )

    private fun boundsJson(bounds: AutomationNodeBounds) =
        buildJsonObject {
            put("left", JsonPrimitive(bounds.left))
            put("top", JsonPrimitive(bounds.top))
            put("right", JsonPrimitive(bounds.right))
            put("bottom", JsonPrimitive(bounds.bottom))
        }

    private fun text(
        args: JsonObject,
        key: String,
    ): String = args.getValue(key).jsonPrimitive.content

    private fun str(max: Int) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("maxLength", JsonPrimitive(max))
        }

    private fun bool() = buildJsonObject { put("type", JsonPrimitive("boolean")) }

    private fun number() = buildJsonObject { put("type", JsonPrimitive("number")) }

    private fun integer() = buildJsonObject { put("type", JsonPrimitive("integer")) }

    private fun choices(values: List<String>) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("enum", JsonArray(values.map(::JsonPrimitive)))
        }

    private fun array(
        item: JsonObject,
        max: Int,
    ) = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", item)
        put("maxItems", JsonPrimitive(max))
    }

    private fun obj(
        fields: Map<String, JsonObject> = emptyMap(),
        vararg required: String,
    ) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(fields))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    companion object {
        const val DEVICE = "ui.device"
        const val APPS = "ui.apps"
        const val LAUNCH = "ui.launch"
        const val SYSTEM = "ui.system"
        const val GESTURE = "ui.gesture"
        const val SCREENSHOT = "ui.screenshot"
    }
}

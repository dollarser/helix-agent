package com.helix.extensions.mobileuse.tools

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.extensions.mobileuse.automation.AutomationDeviceObservation
import com.helix.extensions.mobileuse.automation.AutomationDevicePort
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke
import com.helix.extensions.mobileuse.automation.AutomationToolPort
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
    @Suppress("LongMethod") // Keep the six public device contracts and their capability descriptions together.
    fun descriptors(): List<ToolDescriptor> =
        listOf(
            descriptor(
                DEVICE,
                ToolOperationClass.READ_ONLY,
                obj(),
                deviceOutput(),
                "Observe phone display/window and get a frame for screenshot or gesture. " +
                    "screenBounds is the full display; windowBounds is the active window, " +
                    "not a coordinate origin. A new observation replaces the old frame; " +
                    "semantic node tokens are separate.",
            ),
            descriptor(
                APPS,
                ToolOperationClass.READ_ONLY,
                obj(mapOf("packageName" to str(255))),
                appsOutput(),
                "Verify installation/uninstallation with packageName: returns INSTALLED, NOT_INSTALLED, or UNKNOWN " +
                    "for the current Android user. Use the target app package, " +
                    "not the installer or download source package. " +
                    "UNKNOWN is not proof of absence. " +
                    "Without packageName, list visible launchable apps only; missing entries do not prove uninstall.",
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
                    "One point taps/holds: use 80-120 ms for a normal tap; 500+ ms is a deliberate long press. " +
                    "Several points form a path. " +
                    "Parallel strokes use multiple fingers. " +
                    "Root/Shizuku gestures support up to 10 strokes and 10 seconds without Accessibility. " +
                    "Map image pixels via that screenshot's screenBounds; never subtract windowBounds. " +
                    "Refresh frame after window/rotation changes. " +
                    "Observe after uncertain outcomes; never blindly replay.",
            ),
            descriptor(
                SCREENSHOT,
                ToolOperationClass.LOCAL_MUTATION,
                obj(mapOf("frame" to str(64)), "frame"),
                screenshotOutput(),
                "Capture a ui.device frame into a PNG artifact and the shared vision pipeline. " +
                    "Root/Shizuku capture needs whole-phone scope; scoped window capture needs Accessibility API34+. " +
                    "Protected surfaces can fail. " +
                    "Pixels require a vision-capable model and existing disclosure. " +
                    "width/height describe the PNG; imageWidth/imageHeight describe attached pixels. " +
                    "screenX=left+x*(right-left)/imageWidth; screenY=top+y*(bottom-top)/imageHeight. " +
                    "When this is a fallback after semantic targeting failed and the visual target is clear, " +
                    "a visual gesture may work for custom-drawn controls. Missing nodes do " +
                    "not prove sensitivity. Android can hide sensitive controls and reject " +
                    "Accessibility touches; pixels do not remove that restriction.",
            ),
        )

    fun executor(name: String): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                beforeDispatch(call)?.let { return it }
                return try {
                    when (name) {
                        DEVICE -> {
                            ToolExecutorResult.Completed(deviceJson(port.forCall(call).observe()))
                        }

                        APPS -> {
                            apps(call)
                        }

                        LAUNCH -> {
                            port.forCall(call).launch(text(call.args, "packageName"), call).toToolOutcome()
                        }

                        SYSTEM -> {
                            val requested = text(call.args, "action").uppercase(Locale.ROOT)
                            val action = AutomationGlobalAction.valueOf(requested)
                            actions.forCall(call).globalAction(action).toToolOutcome()
                        }

                        GESTURE -> {
                            port
                                .forCall(
                                    call,
                                ).gesture(text(call.args, "frame"), strokes(call.args), call)
                                .toToolOutcome()
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

    private fun apps(call: ExecutableToolCall): ToolExecutorResult {
        val listing = port.forCall(call).apps(call.args["packageName"]?.jsonPrimitive?.content)
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
                listing.queriedPackage?.let { put("packageName", JsonPrimitive(it)) }
                put("apps", JsonArray(items))
            },
        )
    }

    @Suppress("ReturnCount") // Failed capture and cancelled pre-publication calls do not create artifacts.
    private fun screenshot(call: ExecutableToolCall): ToolExecutorResult {
        val capture = port.forCall(call).screenshot(text(call.args, "frame"), call)
        val bytes =
            capture.png ?: return ToolExecutorResult.Completed(
                buildJsonObject {
                    put("status", JsonPrimitive(capture.status))
                },
            )
        beforeDispatch(call)?.let { return it }
        val published = images.publish(call, bytes, capture.acquisitionScopeRef)
        val output =
            buildJsonObject {
                put("status", JsonPrimitive("SAVED"))
                put("frame", JsonPrimitive(text(call.args, "frame")))
                put("reference", JsonPrimitive(published.reference))
                put("sha256", JsonPrimitive(published.sha256))
                put("sizeBytes", JsonPrimitive(published.sizeBytes))
                put("width", JsonPrimitive(capture.width))
                put("height", JsonPrimitive(capture.height))
                put("imageWidth", JsonPrimitive(published.visual?.width ?: capture.width))
                put("imageHeight", JsonPrimitive(published.visual?.height ?: capture.height))
                put("pixelsAttached", JsonPrimitive(published.visual != null))
                put(
                    "actionHint",
                    JsonPrimitive(
                        if (published.visual != null) {
                            "Pixels are attached. If this screenshot was taken because semantic targeting failed " +
                                "and the intended visual target is now clear, you may try ui.gesture with this same " +
                                "frame and the mapped screen coordinates. Do not repeat equivalent semantic searches " +
                                "or end with a future-intent statement. Missing nodes have " +
                                "unknown cause; pixels do not prove touch acceptance. If an" +
                                " observed attempt has no effect, reassess instead of " +
                                "repeating the same gesture. Accessibility touches may be " +
                                "filtered on sensitive controls; request a manual step when" +
                                " needed."
                        } else {
                            "Pixels were not attached. Do not infer image content or guess coordinates."
                        },
                    ),
                )
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
            put("shizukuState", JsonPrimitive(observation.shizukuState.name))
            put("rootState", JsonPrimitive(observation.rootState.name))
            put("clickMatchBackend", JsonPrimitive(observation.clickMatchBackend.name.lowercase()))
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
                put("screenBounds", boundsJson(AutomationNodeBounds(0, 0, frame.target.width, frame.target.height)))
                put("windowBounds", boundsJson(frame.target.bounds))
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
        ToolVersion(
            if (name == DEVICE) {
                4
            } else if (name == SCREENSHOT || name == APPS) {
                2
            } else {
                1
            },
        ),
        help,
        input,
        output,
        operation,
        75.seconds,
        512L * 1024L,
        setOf(Capability.MOBILE_USE),
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
                "shizukuState" to str(32),
                "rootState" to str(32),
                "clickMatchBackend" to str(32),
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
                "windowBounds" to boundsSchema(),
            ),
            "status",
        )

    private fun appsOutput(): JsonObject {
        val entry = obj(mapOf("packageName" to str(255), "label" to str(256)), "packageName", "label")
        return obj(
            mapOf(
                "truncated" to bool(),
                "status" to str(128),
                "packageName" to str(255),
                "apps" to array(entry, 1_000),
            ),
            "status",
            "apps",
        )
    }

    private fun screenshotOutput() =
        obj(
            mapOf(
                "status" to str(128),
                "frame" to str(64),
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
                "actionHint" to str(768),
            ),
            "status",
        )

    private fun actionOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "observationRequired" to bool(),
                "nextObservation" to str(128),
                "actionHint" to str(384),
            ),
            "status",
        )

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

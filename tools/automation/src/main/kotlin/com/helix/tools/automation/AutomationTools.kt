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
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

interface AutomationToolPort {
    fun snapshot(): AutomationSnapshotResult

    fun nodeAction(request: AutomationNodeActionRequest): AutomationActionResult

    fun globalAction(action: AutomationGlobalAction): AutomationActionResult
}

class PermissionCenterAutomationToolPort(
    private val center: AutomationPermissionCenter,
) : AutomationToolPort {
    override fun snapshot() = center.snapshot()

    override fun nodeAction(request: AutomationNodeActionRequest) = center.performNodeAction(request)

    override fun globalAction(action: AutomationGlobalAction) = center.performGlobalAction(action)
}

/** Token-only Agent tools over the HXA-091..093 accepted Accessibility contracts. */
@Suppress("TooManyFunctions") // schema helpers stay beside the versioned contracts
class AutomationTools(
    private val port: AutomationToolPort,
) {
    fun descriptors(): List<ToolDescriptor> =
        listOf(
            descriptor(SNAPSHOT, ToolOperationClass.READ_ONLY, emptyObject(), snapshotOutput()),
            descriptor(FIND, ToolOperationClass.READ_ONLY, findInput(), findOutput()),
            descriptor(CLICK, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(LONG_CLICK, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(SET_TEXT, ToolOperationClass.EXTERNAL_ACTION, textInput(), actionOutput()),
            descriptor(SET_PROGRESS, ToolOperationClass.EXTERNAL_ACTION, progressInput(), actionOutput()),
            descriptor(SCROLL, ToolOperationClass.EXTERNAL_ACTION, scrollInput(), actionOutput()),
            descriptor(BACK, ToolOperationClass.EXTERNAL_ACTION, emptyObject(), actionOutput()),
            descriptor(HOME, ToolOperationClass.EXTERNAL_ACTION, emptyObject(), actionOutput()),
            descriptor(WAIT, ToolOperationClass.READ_ONLY, waitInput(), findOutput()),
        )

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) {
        descriptors().forEach { descriptor ->
            registry.register(descriptor)
            implementations.register(descriptor, executor(descriptor.name.value))
        }
    }

    fun executor(name: String): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return try {
                    when (name) {
                        SNAPSHOT -> {
                            ToolExecutorResult.Completed(snapshotJson(port.snapshot()))
                        }

                        FIND -> {
                            ToolExecutorResult.Completed(findJson(port.snapshot(), query(call.args)))
                        }

                        CLICK -> {
                            action(port.nodeAction(nodeRequest(AutomationNodeAction.CLICK, call.args)))
                        }

                        LONG_CLICK -> {
                            action(port.nodeAction(nodeRequest(AutomationNodeAction.LONG_CLICK, call.args)))
                        }

                        SET_TEXT -> {
                            action(port.nodeAction(nodeRequest(AutomationNodeAction.SET_TEXT, call.args)))
                        }

                        SET_PROGRESS -> {
                            action(
                                port.nodeAction(nodeRequest(AutomationNodeAction.SET_PROGRESS, call.args)),
                            )
                        }

                        SCROLL -> {
                            action(port.nodeAction(nodeRequest(scrollAction(call.args), call.args)))
                        }

                        BACK -> {
                            action(port.globalAction(AutomationGlobalAction.BACK))
                        }

                        HOME -> {
                            action(port.globalAction(AutomationGlobalAction.HOME))
                        }

                        WAIT -> {
                            wait(call)
                        }

                        else -> {
                            ToolExecutorResult.Failed("AUTOMATION_TOOL_UNKNOWN", sideEffectFree = true)
                        }
                    }
                } catch (error: IllegalArgumentException) {
                    ToolExecutorResult.Failed(error.message ?: "AUTOMATION_ARGUMENT_INVALID", sideEffectFree = true)
                }
            }
        }

    @Suppress("ReturnCount") // cancellation and found are terminal bounded exits
    private fun wait(call: ExecutableToolCall): ToolExecutorResult {
        val timeoutMillis = optionalInt(call.args, "timeoutMillis", 2_000)
        val pollMillis = optionalInt(call.args, "pollMillis", 100)
        val end = minOf(call.deadline, Instant.now().plusMillis(timeoutMillis.toLong()))
        var latest = AutomationSnapshotResult(AutomationSnapshotStatus.UNSUPPORTED_UI)
        while (Instant.now().isBefore(end)) {
            if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
            latest = port.snapshot()
            if (latest.pauseReason != null ||
                latest.status !in setOf(AutomationSnapshotStatus.SUCCESS, AutomationSnapshotStatus.UNSUPPORTED_UI)
            ) {
                return ToolExecutorResult.Completed(findJson(latest, query(call.args)))
            }
            val found = find(latest, query(call.args))
            if (found.status ==
                AutomationFindStatus.FOUND
            ) {
                return ToolExecutorResult.Completed(findJson(latest, query(call.args)))
            }
            Thread.sleep(pollMillis.toLong())
        }
        return ToolExecutorResult.Completed(findJson(latest, query(call.args)))
    }

    private fun find(
        result: AutomationSnapshotResult,
        query: AutomationFindQuery,
    ): AutomationFindResult =
        result.snapshot?.let { AutomationFinder.find(it, query) }
            ?: AutomationFindResult(AutomationFindStatus.NOT_FOUND)

    private fun findJson(
        result: AutomationSnapshotResult,
        query: AutomationFindQuery,
    ): JsonObject {
        val found = find(result, query)
        return buildJsonObject {
            put("status", JsonPrimitive(if (result.snapshot == null) result.status.name else found.status.name))
            put("nodes", JsonArray(found.nodes.map(::nodeJson)))
            recoveryJson(result).forEach { (key, value) -> put(key, value) }
        }
    }

    private fun snapshotJson(result: AutomationSnapshotResult): JsonObject =
        buildJsonObject {
            put("status", JsonPrimitive(result.status.name))
            recoveryJson(result).forEach { (key, value) -> put(key, value) }
            result.snapshot?.let { snapshot ->
                put("packageName", JsonPrimitive(snapshot.packageName))
                put("windowId", JsonPrimitive(snapshot.windowId))
                put("generation", JsonPrimitive(snapshot.generation))
                put("truncated", JsonPrimitive(snapshot.truncated))
                put("nodes", JsonArray(snapshot.nodes.map(::nodeJson)))
            }
        }

    private fun recoveryJson(result: AutomationSnapshotResult): JsonObject =
        buildJsonObject {
            result.targetPackage?.let { put("targetPackage", JsonPrimitive(it)) }
            result.pauseReason?.let { put("pauseReason", JsonPrimitive(it.name)) }
            if (result.pauseReason != null || result.status == AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED) {
                put("requiresUserConfirmation", JsonPrimitive(true))
                put(
                    "recoveryHint",
                    JsonPrimitive(
                        "Stop tool retries and ask the user to confirm recovery in Settings > Permissions > " +
                            "Accessibility automation. A new target needs explicit authorization.",
                    ),
                )
            }
        }

    private fun nodeJson(node: AutomationSnapshotNode) =
        buildJsonObject {
            put("token", JsonPrimitive(node.token))
            put("parentToken", JsonPrimitive(node.parentToken ?: ""))
            put("depth", JsonPrimitive(node.depth))
            put("className", JsonPrimitive(node.className ?: ""))
            put("text", JsonPrimitive(node.text ?: ""))
            put(
                "contentDescription",
                JsonPrimitive(node.contentDescription ?: ""),
            )
            put("viewId", JsonPrimitive(node.viewId ?: ""))
            put("clickable", JsonPrimitive(node.clickable))
            put("longClickable", JsonPrimitive(node.longClickable))
            put("editable", JsonPrimitive(node.editable))
            put("scrollable", JsonPrimitive(node.scrollable))
            put("enabled", JsonPrimitive(node.enabled))
            put("canSetProgress", JsonPrimitive(node.canSetProgress))
            node.range?.let { range ->
                put(
                    "range",
                    buildJsonObject {
                        put("min", JsonPrimitive(range.min))
                        put("max", JsonPrimitive(range.max))
                        put("current", JsonPrimitive(range.current))
                    },
                )
            }
        }

    private fun action(result: AutomationActionResult): ToolExecutorResult =
        if (result.status == AutomationActionStatus.SUCCEEDED) {
            ToolExecutorResult.Completed(buildJsonObject { put("status", JsonPrimitive(result.status.name)) })
        } else {
            ToolExecutorResult.Failed(result.status.name, sideEffectFree = true)
        }

    private fun nodeRequest(
        action: AutomationNodeAction,
        args: JsonObject,
    ) = AutomationNodeActionRequest(
        action,
        requiredString(args, "token"),
        args["text"]?.jsonPrimitive?.contentOrNull,
        args["value"]?.jsonPrimitive?.doubleOrNull,
    )

    private fun scrollAction(args: JsonObject) =
        when (requiredString(args, "direction")) {
            "forward" -> AutomationNodeAction.SCROLL_FORWARD
            "backward" -> AutomationNodeAction.SCROLL_BACKWARD
            else -> throw IllegalArgumentException("AUTOMATION_DIRECTION_INVALID")
        }

    private fun query(args: JsonObject) =
        AutomationFindQuery(
            text = args["text"]?.jsonPrimitive?.contentOrNull,
            contentDescription = args["contentDescription"]?.jsonPrimitive?.contentOrNull,
            viewId = args["viewId"]?.jsonPrimitive?.contentOrNull,
            className = args["className"]?.jsonPrimitive?.contentOrNull,
            clickable = args["clickable"]?.jsonPrimitive?.booleanOrNull,
            match =
                if (args["match"]?.jsonPrimitive?.contentOrNull ==
                    "contains"
                ) {
                    AutomationTextMatch.CONTAINS
                } else {
                    AutomationTextMatch.EXACT
                },
            maxResults = optionalInt(args, "maxResults", 20),
        )

    private fun descriptor(
        name: String,
        operation: ToolOperationClass,
        input: JsonObject,
        output: JsonObject,
    ) = ToolDescriptor(
        ToolName(name),
        ToolVersion(if (name in setOf(SCROLL, SNAPSHOT, FIND, WAIT)) 2 else 1),
        "Bounded token-only Accessibility operation: $name. " +
            "Each snapshot/find replaces all earlier tokens; use tokens from the latest observation. " +
            "On requiresUserConfirmation, SESSION_PAUSED or CHECKPOINT_REQUIRED, " +
            "stop retrying and ask the user to resume. " +
            "scroll operates a scrollable node, not a screen swipe or app drawer gesture. " +
            "Use ui.set_progress with range.min/max for a slider that reports canSetProgress.",
        input,
        output,
        operation,
        15.seconds,
        MAX_OUTPUT_BYTES,
        setOf(Capability.ACCESSIBILITY_AUTOMATION),
        if (operation == ToolOperationClass.READ_ONLY) Idempotency.IDEMPOTENT else Idempotency.NON_IDEMPOTENT,
        ExecutionTargetType.LOCAL_ANDROID,
        ToolOrigin.BuiltInOrigin,
    )

    private fun emptyObject() = obj(emptyMap(), emptyList())

    private fun tokenInput() = obj(mapOf("token" to str(32)), listOf("token"))

    private fun textInput() = obj(mapOf("token" to str(32), "text" to str(2_000)), listOf("token", "text"))

    private fun number() = buildJsonObject { put("type", JsonPrimitive("number")) }

    private fun progressInput() = obj(mapOf("token" to str(32), "value" to number()), listOf("token", "value"))

    private fun scrollInput() =
        obj(
            mapOf(
                "token" to str(32),
                "direction" to
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put("enum", JsonArray(listOf(JsonPrimitive("forward"), JsonPrimitive("backward"))))
                        put("description", JsonPrimitive("Scroll the token container using forward or backward."))
                    },
            ),
            listOf("token", "direction"),
        )

    private fun findInput() = querySchema(includeWait = false)

    private fun waitInput() = querySchema(includeWait = true)

    private fun querySchema(includeWait: Boolean): JsonObject {
        val fields =
            linkedMapOf(
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "className" to str(512),
                "clickable" to bool(),
                "match" to str(8),
                "maxResults" to integer(1, 50),
            )
        if (includeWait) {
            fields["timeoutMillis"] = integer(1, 10_000)
            fields["pollMillis"] = integer(50, 1_000)
        }
        return obj(fields, emptyList())
    }

    private fun actionOutput() = obj(mapOf("status" to str(64)), listOf("status"))

    private fun findOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "targetPackage" to str(255),
                "pauseReason" to str(64),
                "requiresUserConfirmation" to bool(),
                "recoveryHint" to str(512),
                "nodes" to array(nodeSchema(), 50),
            ),
            listOf("status", "nodes"),
        )

    private fun snapshotOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "targetPackage" to str(255),
                "pauseReason" to str(64),
                "requiresUserConfirmation" to bool(),
                "recoveryHint" to str(512),
                "packageName" to str(255),
                "windowId" to integer(0),
                "generation" to integer(0),
                "truncated" to bool(),
                "nodes" to array(nodeSchema(), 200),
            ),
            listOf("status"),
        )

    private fun nodeSchema() =
        obj(
            mapOf(
                "token" to str(32),
                "parentToken" to str(32),
                "depth" to integer(0),
                "className" to str(512),
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "clickable" to bool(),
                "longClickable" to bool(),
                "editable" to bool(),
                "scrollable" to bool(),
                "enabled" to bool(),
                "canSetProgress" to bool(),
                "range" to
                    obj(
                        mapOf("min" to number(), "max" to number(), "current" to number()),
                        listOf("min", "max", "current"),
                    ),
            ),
            listOf(
                "token",
                "parentToken",
                "depth",
                "className",
                "text",
                "contentDescription",
                "viewId",
                "clickable",
                "longClickable",
                "editable",
                "scrollable",
                "enabled",
            ),
        )

    private fun obj(
        properties: Map<String, JsonObject>,
        required: List<String>,
    ) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        if (properties.isNotEmpty()) {
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (k, v) ->
                        put(k, v)
                    }
                },
            )
        }
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    private fun str(max: Int) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("maxLength", JsonPrimitive(max))
        }

    private fun integer(
        min: Int,
        max: Int? = null,
    ) = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("minimum", JsonPrimitive(min))
        max?.let { put("maximum", JsonPrimitive(it)) }
    }

    private fun bool() = buildJsonObject { put("type", JsonPrimitive("boolean")) }

    private fun array(
        items: JsonObject,
        max: Int,
    ) = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("maxItems", JsonPrimitive(max))
    }

    private fun requiredString(
        args: JsonObject,
        key: String,
    ) = requireNotNull(args[key]?.jsonPrimitive?.contentOrNull) {
        "AUTOMATION_ARGUMENT_MISSING_$key"
    }

    private fun optionalInt(
        args: JsonObject,
        key: String,
        default: Int,
    ) = args[key]?.jsonPrimitive?.intOrNull ?: default

    companion object {
        const val SNAPSHOT = "ui.snapshot"
        const val FIND = "ui.find"
        const val CLICK = "ui.click"
        const val LONG_CLICK = "ui.long_click"
        const val SET_TEXT = "ui.set_text"
        const val SET_PROGRESS = "ui.set_progress"
        const val SCROLL = "ui.scroll"
        const val BACK = "ui.back"
        const val HOME = "ui.home"
        const val WAIT = "ui.wait"
        const val MAX_OUTPUT_BYTES = 512L * 1024L
    }
}

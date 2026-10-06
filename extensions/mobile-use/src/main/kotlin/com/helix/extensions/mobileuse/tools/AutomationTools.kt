package com.helix.extensions.mobileuse.tools

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.extensions.mobileuse.automation.AUTOMATION_MAX_SET_TEXT
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationFindQuery
import com.helix.extensions.mobileuse.automation.AutomationFindResult
import com.helix.extensions.mobileuse.automation.AutomationFindStatus
import com.helix.extensions.mobileuse.automation.AutomationFinder
import com.helix.extensions.mobileuse.automation.AutomationGlobalAction
import com.helix.extensions.mobileuse.automation.AutomationNodeAction
import com.helix.extensions.mobileuse.automation.AutomationNodeActionRequest
import com.helix.extensions.mobileuse.automation.AutomationPrivilegedSelector
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import com.helix.extensions.mobileuse.automation.AutomationSnapshotResult
import com.helix.extensions.mobileuse.automation.AutomationSnapshotStatus
import com.helix.extensions.mobileuse.automation.AutomationTextMatch
import com.helix.extensions.mobileuse.automation.AutomationToolPort
import com.helix.extensions.mobileuse.automation.AutomationWaitCondition
import com.helix.extensions.mobileuse.automation.AutomationWaitStatus
import com.helix.extensions.mobileuse.automation.AutomationWaiter
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
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

/** Token-only Agent tools over the HXA-091..093 accepted Accessibility contracts. */
@Suppress("TooManyFunctions") // schema helpers stay beside the versioned contracts
class AutomationTools(
    private val port: AutomationToolPort,
    private val origin: ToolOrigin = ToolOrigin.BuiltInOrigin,
) {
    fun descriptors(): List<ToolDescriptor> =
        listOf(
            descriptor(SNAPSHOT, ToolOperationClass.READ_ONLY, emptyObject(), snapshotOutput()),
            descriptor(FIND, ToolOperationClass.READ_ONLY, findInput(), findOutput()),
            descriptor(CLICK, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(CLICK_MATCH, ToolOperationClass.EXTERNAL_ACTION, clickMatchInput(), actionOutput()),
            descriptor(LONG_CLICK, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(SET_TEXT, ToolOperationClass.EXTERNAL_ACTION, textInput(), actionOutput()),
            descriptor(IME_ENTER, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(SET_PROGRESS, ToolOperationClass.EXTERNAL_ACTION, progressInput(), actionOutput()),
            descriptor(SCROLL, ToolOperationClass.EXTERNAL_ACTION, scrollInput(), actionOutput()),
            descriptor(BACK, ToolOperationClass.EXTERNAL_ACTION, emptyObject(), actionOutput()),
            descriptor(HOME, ToolOperationClass.EXTERNAL_ACTION, emptyObject(), actionOutput()),
            descriptor(WAIT, ToolOperationClass.READ_ONLY, waitInput(), findOutput()),
        )

    fun register(registry: ToolRegistry) {
        descriptors().forEach { descriptor ->

            registry.register(descriptor, executor(descriptor.name.value))
        }
    }

    fun executor(name: String): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val boundPort = port.forCall(call)
                return try {
                    when (name) {
                        SNAPSHOT -> {
                            ToolExecutorResult.Completed(snapshotJson(boundPort.snapshot()))
                        }

                        FIND -> {
                            ToolExecutorResult.Completed(findJson(boundPort.snapshot(), query(call.args)))
                        }

                        CLICK -> {
                            action(boundPort.nodeAction(nodeRequest(AutomationNodeAction.CLICK, call.args)))
                        }

                        CLICK_MATCH -> {
                            clickMatch(boundPort, call)
                        }

                        LONG_CLICK -> {
                            action(boundPort.nodeAction(nodeRequest(AutomationNodeAction.LONG_CLICK, call.args)))
                        }

                        SET_TEXT -> {
                            action(boundPort.nodeAction(nodeRequest(AutomationNodeAction.SET_TEXT, call.args)))
                        }

                        IME_ENTER -> {
                            action(boundPort.nodeAction(nodeRequest(AutomationNodeAction.IME_ENTER, call.args)))
                        }

                        SET_PROGRESS -> {
                            action(
                                boundPort.nodeAction(nodeRequest(AutomationNodeAction.SET_PROGRESS, call.args)),
                            )
                        }

                        SCROLL -> {
                            action(boundPort.nodeAction(nodeRequest(scrollAction(call.args), call.args)))
                        }

                        BACK -> {
                            action(boundPort.globalAction(AutomationGlobalAction.BACK))
                        }

                        HOME -> {
                            action(boundPort.globalAction(AutomationGlobalAction.HOME))
                        }

                        WAIT -> {
                            wait(call, boundPort)
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

    private fun clickMatch(
        boundPort: AutomationToolPort,
        call: ExecutableToolCall,
    ): ToolExecutorResult {
        require("backend" !in call.args) { "BACKEND_IS_HOST_SELECTED" }
        val exact =
            call.args.keys.all { it in setOf("packageName", "viewId", "text") } &&
                listOf("packageName", "viewId", "text").all { it in call.args }
        val backend = boundPort.preferredClickBackend().name.lowercase()
        if (backend in setOf("root", "shizuku") && exact) {
            require(exact) {
                "PRIVILEGED_REQUIRES_EXACT_PACKAGE_VIEW_ID_TEXT"
            }
            val selector =
                AutomationPrivilegedSelector(
                    requireNotNull(call.args["packageName"]?.jsonPrimitive?.contentOrNull),
                    requireNotNull(call.args["viewId"]?.jsonPrimitive?.contentOrNull),
                    requireNotNull(call.args["text"]?.jsonPrimitive?.contentOrNull),
                )
            return withClickBackend(
                action(if (backend == "root") boundPort.rootClick(selector) else boundPort.shizukuClick(selector)),
                backend,
            )
        }
        return withClickBackend(
            AutomationClickMatchExecutor(boundPort).execute(
                call = call,
                query = query(call.args).copy(maxResults = 50),
                timeoutMillis = optionalInt(call.args, "timeoutMillis", 0),
                pollMillis = optionalInt(call.args, "pollMillis", 100),
                packageName = call.args["packageName"]?.jsonPrimitive?.contentOrNull,
            ),
            backend,
        )
    }

    private fun withClickBackend(
        result: ToolExecutorResult,
        backend: String,
    ): ToolExecutorResult =
        if (result is ToolExecutorResult.Completed) {
            result.copy(output = JsonObject((result.output as JsonObject) + ("backend" to JsonPrimitive(backend))))
        } else {
            result
        }

    @Suppress("ReturnCount") // Read-only timeout and cancellation remain distinct from condition results.
    private fun wait(call: ExecutableToolCall, boundPort: AutomationToolPort): ToolExecutorResult {
        val requested = java.time.Duration.ofMillis(optionalInt(call.args, "timeoutMillis", 2_000).toLong())
        val available = java.time.Duration.between(Instant.now(), call.deadline)
        if (available.isNegative || available.isZero) return ToolExecutorResult.TimedOut
        val condition =
            AutomationWaitCondition.valueOf(
                (call.args["condition"]?.jsonPrimitive?.contentOrNull ?: "present").uppercase(java.util.Locale.ROOT),
            )
        val result =
            AutomationWaiter().waitFor(
                query(call.args),
                minOf(requested, available),
                java.time.Duration.ofMillis(optionalInt(call.args, "pollMillis", 100).toLong()),
                condition,
                java.time.Duration.ofMillis(optionalInt(call.args, "stableMillis", 500).toLong()),
                cancelled = { call.cancel.isCancelled() },
                snapshotProvider = boundPort::snapshot,
            )
        if (result.status == AutomationWaitStatus.CANCELLED) return ToolExecutorResult.Cancelled
        return ToolExecutorResult.Completed(
            buildJsonObject {
                val observation = result.observation
                put(
                    "status",
                    JsonPrimitive(
                        if (result.status == AutomationWaitStatus.SNAPSHOT_REFUSED) {
                            observation?.status?.name ?: result.status.name
                        } else {
                            result.status.name
                        },
                    ),
                )
                put("waitStatus", JsonPrimitive(result.status.name))
                val nodeIndex =
                    observation
                        ?.snapshot
                        ?.nodes
                        .orEmpty()
                        .associateBy { it.token }
                put("nodes", JsonArray(result.matches.map { AutomationNodeJson.full(it, nodeIndex) }))
                putClickSuggestion(result.matches, nodeIndex)?.forEach { (key, value) -> put(key, value) }
                if (result.status == AutomationWaitStatus.TIMED_OUT) {
                    put(
                        "recoveryHint",
                        JsonPrimitive(
                            "The requested condition was not observed before the deadline. " +
                                "This does not prove the previous action failed; inspect the current state " +
                                "before selecting another action. Do not repeat unchanged waits " +
                                "or replay an uncertain effect.",
                        ),
                    )
                }
                observation?.let { recoveryJson(it).forEach { (key, value) -> put(key, value) } }
            },
        )
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
        val nodeIndex =
            result.snapshot
                ?.nodes
                .orEmpty()
                .associateBy { it.token }
        return buildJsonObject {
            put("status", JsonPrimitive(if (result.snapshot == null) result.status.name else found.status.name))
            put("nodes", JsonArray(found.nodes.map { AutomationNodeJson.full(it, nodeIndex) }))
            putClickSuggestion(found.nodes, nodeIndex)?.forEach { (key, value) -> put(key, value) }
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
                val nodeIndex = snapshot.nodes.associateBy { it.token }
                // Keep the complete index for click ancestors, but omit empty layout containers from model output.
                val visibleNodes =
                    snapshot.nodes.filter {
                        !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() ||
                            it.clickable || it.longClickable || it.editable || it.scrollable ||
                            it.checkable || it.canSetProgress || it.redacted
                    }
                put("nodes", JsonArray(visibleNodes.map { AutomationNodeJson.compact(it, nodeIndex) }))
            }
        }

    private fun snapshotRecoveryHint(
        snapshot: com.helix.extensions.mobileuse.automation.AutomationSnapshot,
        backend: String?,
    ): String? =
        when {
            snapshot.nodes.any { it.redacted } -> {
                PROTECTED_HINT
            }

            snapshot.truncated -> {
                "The captured tree is incomplete; result pages cannot recover omitted nodes. " +
                    "If the target is missing, use ui.device then ui.screenshot. " +
                    "Perform the next permitted tool call; do not end with a plan to do it."
            }

            backend == "accessibility" && snapshot.packageName.endsWith(".packageinstaller") -> {
                "Android may hide installer confirmation and filter ordinary Accessibility touches. " +
                    "If the required control is missing, do not repeat equivalent semantic queries. " +
                    "A permitted screenshot can locate controls but cannot remove touch " +
                    "filtering. If an observed action has no effect, " +
                    "request the manual confirmation. Never report installation without verification."
            }

            else -> {
                null
            }
        }

    private fun recoveryJson(result: AutomationSnapshotResult): JsonObject =
        buildJsonObject {
            result.snapshot?.let { snapshot ->
                put("truncated", JsonPrimitive(snapshot.truncated))
                put("truncationReasons", JsonArray(snapshot.truncationReasons.map(::JsonPrimitive)))
                snapshotRecoveryHint(snapshot, result.backend)?.let { put("recoveryHint", JsonPrimitive(it)) }
            }
            result.backend?.let { put("backend", JsonPrimitive(it)) }
            if (result.pauseReason == null && result.status in
                setOf(
                    AutomationSnapshotStatus.SERVICE_NOT_CONNECTED,
                    AutomationSnapshotStatus.NO_ACTIVE_SESSION,
                    AutomationSnapshotStatus.UNSUPPORTED_UI,
                )
            ) {
                put(
                    "recoveryHint",
                    JsonPrimitive(
                        "Semantic observation is unavailable; " +
                            "this does not establish that the screen has no controls. " +
                            "Check ui.device for an independently authorized screenshot/gesture backend. " +
                            "If available, " +
                            "use ui.screenshot and act only on a visible target with ui.gesture. " +
                            "Do not repeat equivalent semantic queries or bypass authorization; " +
                            "otherwise report the limitation.",
                    ),
                )
            }
            result.targetPackage?.let { put("targetPackage", JsonPrimitive(it)) }
            result.pauseReason?.let { put("pauseReason", JsonPrimitive(it.name)) }
            if (result.pauseReason != null || result.status == AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED) {
                put(
                    "requiresAuthorization",
                    JsonPrimitive(result.status == AutomationSnapshotStatus.TARGET_NOT_ALLOWLISTED),
                )
                put(
                    "recoveryHint",
                    JsonPrimitive(
                        "Inspect the current target with ui.snapshot. A verified return to an already authorized " +
                            "target resumes automatically. A new target needs authorization; never bypass it. " +
                            "If no permitted path exists, report the incomplete work and stop.",
                    ),
                )
            }
        }

    private fun putClickSuggestion(
        nodes: List<AutomationSnapshotNode>,
        nodeIndex: Map<String, AutomationSnapshotNode>,
    ): JsonObject? {
        if (nodes.isNotEmpty() && nodes.all(::automationNodeOffscreen)) {
            return buildJsonObject {
                put(
                    "actionHint",
                    JsonPrimitive(
                        "Matches are offscreen. Scroll a fresh scrollable container token with ui.scroll, " +
                            "then observe again. " +
                            "Do not click these tokens or guess coordinates.",
                    ),
                )
            }
        }
        val token =
            nodes
                .map { automationClickTargetToken(it, nodeIndex) }
                .filter(String::isNotEmpty)
                .distinct()
                .singleOrNull()
        return token?.let {
            buildJsonObject {
                put("suggestedAction", JsonPrimitive(CLICK))
                put("suggestedClickToken", JsonPrimitive(token))
                put(
                    "actionHint",
                    JsonPrimitive(
                        "If this match is the next intended control, call ui.click with suggestedClickToken next. " +
                            "Do not search screenshots or coordinates first.",
                    ),
                )
            }
        }
    }

    private fun action(result: AutomationActionResult): ToolExecutorResult = result.toToolOutcome()

    private fun nodeRequest(
        action: AutomationNodeAction,
        args: JsonObject,
    ) = AutomationNodeActionRequest(
        action = action,
        token = requiredString(args, "token"),
        text = args["text"]?.jsonPrimitive?.contentOrNull,
        progress = args["value"]?.jsonPrimitive?.doubleOrNull,
        submit = args["submit"]?.jsonPrimitive?.booleanOrNull ?: false,
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
            checkable = args["checkable"]?.jsonPrimitive?.booleanOrNull,
            checked = args["checked"]?.jsonPrimitive?.booleanOrNull,
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
        ToolVersion(
            if (name in setOf(FIND, WAIT)) {
                8
            } else if (name == CLICK_MATCH) {
                7
            } else if (name == SNAPSHOT) {
                7
            } else if (name == SET_TEXT) {
                3
            } else if (name == SCROLL) {
                3
            } else if (name !in setOf(BACK, HOME)) {
                2
            } else {
                1
            },
        ),
        AutomationToolDescriptions.description(name),
        input,
        output,
        operation,
        if (name in setOf(WAIT, CLICK_MATCH)) 65.seconds else 15.seconds,
        MAX_OUTPUT_BYTES,
        setOf(Capability.MOBILE_USE),
        if (operation == ToolOperationClass.READ_ONLY) Idempotency.IDEMPOTENT else Idempotency.NON_IDEMPOTENT,
        ExecutionTargetType.LOCAL_ANDROID,
        origin,
    )

    private fun emptyObject() = obj(emptyMap(), emptyList())

    private fun tokenInput() = obj(mapOf("token" to str(32)), listOf("token"))

    private fun textInput() =
        obj(
            mapOf(
                "token" to str(32),
                "text" to str(AUTOMATION_MAX_SET_TEXT),
                "submit" to bool(),
            ),
            listOf("token", "text"),
        )

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

    private fun clickMatchInput(): JsonObject {
        val fields =
            linkedMapOf(
                "packageName" to str(255),
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "className" to str(512),
                "clickable" to bool(),
                "match" to str(8),
                "checkable" to bool(),
                "checked" to bool(),
                "maxResults" to integer(1, 50),
                "timeoutMillis" to integer(0, 60_000),
                "pollMillis" to integer(50, 1_000),
            )
        return obj(fields, emptyList())
    }

    private fun querySchema(includeWait: Boolean): JsonObject {
        val fields =
            linkedMapOf(
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "className" to str(512),
                "clickable" to bool(),
                "match" to str(8),
                "checkable" to bool(),
                "checked" to bool(),
                "maxResults" to integer(1, 50),
            )
        if (includeWait) {
            fields["timeoutMillis"] = integer(1, 60_000)
            fields["stableMillis"] = integer(50, 60_000)
            fields["condition"] =
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", JsonArray(listOf("present", "absent", "changed", "stable").map(::JsonPrimitive)))
                }
            fields["pollMillis"] = integer(50, 1_000)
        }
        return obj(fields, emptyList())
    }

    private fun actionOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "observationRequired" to bool(),
                "nextObservation" to str(128),
                "actionHint" to str(384),
                "backend" to str(16),
            ),
            listOf("status"),
        )

    private fun findOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "targetPackage" to str(255),
                "pauseReason" to str(64),
                "requiresAuthorization" to bool(),
                "recoveryHint" to str(512),
                "backend" to str(16),
                "nodes" to array(nodeSchema(), 50),
                "truncated" to bool(),
                "truncationReasons" to array(str(32), 4),
                "waitStatus" to str(64),
                "suggestedAction" to str(32),
                "suggestedClickToken" to str(32),
                "actionHint" to str(256),
            ),
            listOf("status", "nodes"),
        )

    private fun snapshotOutput() =
        obj(
            mapOf(
                "status" to str(64),
                "targetPackage" to str(255),
                "pauseReason" to str(64),
                "requiresAuthorization" to bool(),
                "recoveryHint" to str(512),
                "backend" to str(16),
                "packageName" to str(255),
                "windowId" to integer(0),
                "generation" to integer(0),
                "truncated" to bool(),
                "truncationReasons" to array(str(32), 4),
                "nodes" to array(nodeSchema(compact = true), 200),
            ),
            listOf("status"),
        )

    private fun nodeSchema(compact: Boolean = false) =
        obj(
            mapOf(
                "token" to str(32),
                "parentToken" to str(32),
                "clickTargetToken" to str(32),
                "depth" to integer(0),
                "className" to str(512),
                "text" to str(2_000),
                "contentDescription" to str(2_000),
                "viewId" to str(512),
                "clickable" to bool(),
                "offscreen" to bool(),
                "longClickable" to bool(),
                "checkable" to bool(),
                "checked" to bool(),
                "editable" to bool(),
                "scrollable" to bool(),
                "enabled" to bool(),
                "redacted" to bool(),
                "canImeEnter" to bool(),
                "bounds" to
                    obj(
                        mapOf("left" to number(), "top" to number(), "right" to number(), "bottom" to number()),
                        emptyList(),
                    ),
                "canSetProgress" to bool(),
                "range" to
                    obj(
                        mapOf("min" to number(), "max" to number(), "current" to number()),
                        listOf("min", "max", "current"),
                    ),
            ),
            if (compact) {
                listOf("token", "clickable", "enabled")
            } else {
                listOf(
                    "token",
                    "parentToken",
                    "clickTargetToken",
                    "depth",
                    "className",
                    "text",
                    "contentDescription",
                    "viewId",
                    "clickable",
                    "longClickable",
                    "checkable",
                    "checked",
                    "editable",
                    "scrollable",
                    "enabled",
                    "canImeEnter",
                )
            },
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
        private const val PROTECTED_HINT =
            "Protected fields are redacted. Use only exposed non-redacted controls. " +
                "If the required control is missing, ask the user to handle this screen, then observe again. " +
                "Do not bypass protection with screenshots, coordinates or another backend. " +
                "Do not finish with a promise to take a screenshot without a tool call."

        const val SNAPSHOT = "ui.snapshot"
        const val FIND = "ui.find"
        const val CLICK = "ui.click"
        const val CLICK_MATCH = "ui.click_match"
        const val LONG_CLICK = "ui.long_click"
        const val SET_TEXT = "ui.set_text"
        const val IME_ENTER = "ui.ime_enter"
        const val SET_PROGRESS = "ui.set_progress"
        const val SCROLL = "ui.scroll"
        const val BACK = "ui.back"
        const val HOME = "ui.home"
        const val WAIT = "ui.wait"
        const val MAX_OUTPUT_BYTES = 512L * 1024L
    }
}

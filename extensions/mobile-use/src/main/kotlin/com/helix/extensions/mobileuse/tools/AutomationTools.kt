package com.helix.extensions.mobileuse.tools

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.extensions.mobileuse.AutomationTermination
import com.helix.extensions.mobileuse.automation.AUTOMATION_MAX_SET_TEXT
import com.helix.extensions.mobileuse.automation.AutomationActionResult
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
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
import com.helix.extensions.mobileuse.automation.SnapshotTruncationReasons
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.array
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.bool
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.integer
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.nodeSchema
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.number
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.obj
import com.helix.extensions.mobileuse.tools.AutomationToolSchemas.str
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolJsonNumbers
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
            descriptor(SET_TEXT, ToolOperationClass.EXTERNAL_ACTION, textInput(), actionOutput(observeAfter = true)),
            descriptor(IME_ENTER, ToolOperationClass.EXTERNAL_ACTION, tokenInput(), actionOutput()),
            descriptor(SET_PROGRESS, ToolOperationClass.EXTERNAL_ACTION, progressInput(), actionOutput()),
            descriptor(SCROLL, ToolOperationClass.EXTERNAL_ACTION, scrollInput(), actionOutput(observeAfter = true)),
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
                AutomationTermination.beforeDispatch(call)?.let { return it }
                val boundPort = port.forCall(call)
                return try {
                    val result =
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
                    if (name in setOf(SET_TEXT, SCROLL)) {
                        AutomationPostActionObservation(boundPort::snapshot, ::snapshotJson).attach(result, call)
                    } else {
                        result
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
        if (backend in setOf("root", "adb", "shizuku") && exact && !boundPort.usesBackgroundDisplay()) {
            require(exact) {
                "PRIVILEGED_REQUIRES_EXACT_PACKAGE_VIEW_ID_TEXT"
            }
            val selector =
                AutomationPrivilegedSelector(
                    requireNotNull(call.args["packageName"]?.jsonPrimitive?.contentOrNull),
                    requireNotNull(call.args["viewId"]?.jsonPrimitive?.contentOrNull),
                    requireNotNull(call.args["text"]?.jsonPrimitive?.contentOrNull),
                )
            val result =
                when (backend) {
                    "root" -> boundPort.rootClick(selector)
                    "adb" -> boundPort.adbClick(selector)
                    else -> boundPort.shizukuClick(selector)
                }
            val outcome =
                if (result.status in
                    setOf(
                        AutomationActionStatus.TARGET_NOT_FOUND,
                        AutomationActionStatus.TARGET_AMBIGUOUS,
                    )
                ) {
                    clickMatchRefusal(result.status.name)
                } else {
                    action(result)
                }
            return withClickBackend(outcome, backend)
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
        if (available.isNegative || available.isZero) return AutomationTermination.observationTimedOut
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
        if (result.status == AutomationWaitStatus.CANCELLED) return AutomationTermination.observationCancelled
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
            recoveryJson(result).forEach { (key, value) -> put(key, value) }
            put("matchCount", JsonPrimitive(found.matchCount))
            put("ambiguous", JsonPrimitive(found.matchCount > 1))
            put("truncated", JsonPrimitive(found.truncated))
            result.snapshot?.let { put("observedAtMillis", JsonPrimitive(it.createdAt.toEpochMilli())) }
            if (!found.truncated) {
                putClickSuggestion(found.nodes, nodeIndex)?.forEach { (key, value) ->
                    put(key, value)
                }
            }
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
                put("observedAtMillis", JsonPrimitive(snapshot.createdAt.toEpochMilli()))
                val projection = AutomationSnapshotProjection(snapshot)
                put("nodes", JsonArray(projection.nodes))
                put("returnedNodeCount", JsonPrimitive(projection.nodes.size))
                if (projection.limited) {
                    put("truncated", JsonPrimitive(true))
                    put(
                        "truncationReasons",
                        JsonArray((snapshot.truncationReasons + "OUTPUT_LIMIT").map(::JsonPrimitive)),
                    )
                    if (!snapshot.truncated && snapshot.nodes.none { it.redacted }) {
                        put(
                            "recoveryHint",
                            JsonPrimitive(
                                "Model output is limited to 200 nodes and 128 KiB. " +
                                    "Use ui.find for a specific observed label/attribute in the captured tree. " +
                                    "OUTPUT_LIMIT is separate from collection limits; " +
                                    "do not assume omitted controls are absent.",
                            ),
                        )
                    }
                }
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
            missingTargetHint(result.status.name)?.let { put("recoveryHint", JsonPrimitive(it)) }
            result.snapshot?.let { snapshot ->
                put("truncated", JsonPrimitive(snapshot.truncated))
                put("capturedNodeCount", JsonPrimitive(snapshot.nodes.size))
                put("truncationReasons", JsonArray(snapshot.truncationReasons.map(::JsonPrimitive)))
                snapshotRecoveryHint(snapshot, result.backend)?.let { put("recoveryHint", JsonPrimitive(it)) }
            }
            result.backend?.let { put("backend", JsonPrimitive(it)) }
            if (result.pauseReason == null && result.status in
                setOf(
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
                        "Offscreen matches. Call ui.snapshot or ui.find(scrollable=true); scroll a fresh container " +
                            "token with ui.scroll, then observe. Check scrollDirections: forward/backward may switch " +
                            "tabs. Do not click offscreen tokens or guess coordinates.",
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
                        "If this is the intended control, call ui.click(suggestedClickToken) next. " +
                            "ui.find/ui.snapshot/ui.wait replace tokens: after another query use its fresh result. " +
                            "Never reuse the earlier token.",
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
            "up" -> AutomationNodeAction.SCROLL_UP
            "down" -> AutomationNodeAction.SCROLL_DOWN
            "left" -> AutomationNodeAction.SCROLL_LEFT
            "right" -> AutomationNodeAction.SCROLL_RIGHT
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
            scrollable = args["scrollable"]?.jsonPrimitive?.booleanOrNull,
            editable = args["editable"]?.jsonPrimitive?.booleanOrNull,
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
        ToolVersion(1),
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
        discoveryCompanions = setOf(SNAPSHOT, FIND, LONG_CLICK).filter { it != name }.map(::ToolName).toSet(),
    )

    private fun emptyObject() = obj(emptyMap(), emptyList())

    private fun tokenInput() = obj(mapOf("token" to str(32)), listOf("token"))

    private fun textInput() =
        obj(
            mapOf(
                "token" to str(32),
                "text" to str(AUTOMATION_MAX_SET_TEXT),
                "submit" to
                    buildJsonObject {
                        put("type", JsonPrimitive("boolean"))
                        put("default", JsonPrimitive(false))
                        put(
                            "description",
                            JsonPrimitive(
                                "Omit for filling. True also invokes IME; requires observed canImeEnter=true.",
                            ),
                        )
                    },
            ),
            listOf("token", "text"),
        )

    private fun progressInput() = obj(mapOf("token" to str(32), "value" to number()), listOf("token", "value"))

    private fun scrollInput() =
        obj(
            mapOf(
                "token" to str(32),
                "direction" to
                    buildJsonObject {
                        put("type", JsonPrimitive("string"))
                        put(
                            "enum",
                            JsonArray(
                                listOf("forward", "backward", "up", "down", "left", "right")
                                    .map(::JsonPrimitive),
                            ),
                        )
                        put(
                            "description",
                            JsonPrimitive(
                                "Copy an exact value from the node's scrollDirections; never infer it from " +
                                    "finger motion. " +
                                    "For example, [forward,right] permits right/forward, not left. " +
                                    "Forward/backward follow the widget's axis and can change horizontal tabs.",
                            ),
                        )
                    },
            ),
            listOf("token", "direction"),
        )

    private fun findInput() = querySchema(includeWait = false)

    private fun waitInput() = querySchema(includeWait = true)

    private fun pollIntervalSchema() =
        JsonObject(
            integer(50, 1_000) +
                mapOf(
                    "default" to JsonPrimitive(100),
                    "description" to
                        JsonPrimitive(
                            "Normally omit; the host polls every 100 ms. If supplied, use 50..1000 ms. " +
                                "For a longer wait change timeoutMillis, not pollMillis. " +
                                "Out-of-range values are rejected.",
                        ),
                ),
        )

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
                "pollMillis" to pollIntervalSchema(),
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
        fields["scrollable"] = bool()
        fields["editable"] = bool()
        if (includeWait) {
            fields["timeoutMillis"] = integer(1, 60_000)
            fields["stableMillis"] = integer(50, 60_000)
            fields["condition"] =
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("enum", JsonArray(listOf("present", "absent", "changed", "stable").map(::JsonPrimitive)))
                }
            fields["pollMillis"] = pollIntervalSchema()
        }
        return obj(fields, emptyList())
    }

    private fun actionOutput(observeAfter: Boolean = false): JsonObject {
        val base = automationActionOutputSchema(includeBackend = true)
        if (!observeAfter) return base
        val status =
            buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", JsonArray(AutomationPostActionObservation.STATUSES.map(::JsonPrimitive)))
            }
        return JsonObject(
            base +
                mapOf(
                    "properties" to
                        JsonObject(
                            base.getValue("properties").jsonObject +
                                mapOf(
                                    "observationStatus" to status,
                                    "observation" to snapshotOutput(),
                                ),
                        ),
                    "required" to JsonArray(base.getValue("required").jsonArray + JsonPrimitive("observationStatus")),
                ),
        )
    }

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
                "matchCount" to integer(0),
                "ambiguous" to bool(),
                "observedAtMillis" to integer(0),
                "capturedNodeCount" to integer(0),
                "truncated" to bool(),
                "truncationReasons" to array(str(32), SnapshotTruncationReasons.all.size),
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
                "capturedNodeCount" to integer(0),
                "returnedNodeCount" to integer(0),
                "observedAtMillis" to integer(0),
                "truncated" to bool(),
                "truncationReasons" to array(str(32), SnapshotTruncationReasons.all.size),
                "nodes" to array(nodeSchema(compact = true), AutomationSnapshotProjection.MAX_NODES),
            ),
            listOf("status"),
        )

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
) = if (key !in
    args
) {
    default
} else {
    requireNotNull(ToolJsonNumbers.intOrNull(args[key])) { "AUTOMATION_ARGUMENT_INVALID_$key" }
}

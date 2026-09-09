@file:Suppress("TooManyFunctions") // 3 notifications.*/calendar.* tools share the internal schema/arg helpers

package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object NotificationsQueryTool {
    const val NAME: String = "notifications.query"

    const val VERSION: Int = 1

    @Suppress("LongMethod") // model-facing descriptor kept as one readable block
    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Read the active notifications posted by the apps in [allowedPackages] within the " +
                    "[sinceEpochMillis, untilEpochMillis] window (at most 24h; newest first; bounded to " +
                    "20 entries). Refused with status 'permission-missing' unless the user has enabled " +
                    "Helix's system Notification Listener. Only the allowlisted apps' notifications are " +
                    "returned; the count of in-window notifications filtered out is also reported.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "allowedPackages",
                                notificationsCalendarToolsArraySchema(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put("maxLength", JsonPrimitive(MAX_PACKAGE_LEN))
                                    },
                                    "The exact app package names to read (1..$MAX_ALLOWED_PACKAGES).",
                                ),
                            )
                            put("sinceEpochMillis", integerSchema("Window start (epoch millis, inclusive)."))
                            put(
                                "untilEpochMillis",
                                integerSchema("Window end (epoch millis, inclusive); after since, at most 24h apart."),
                            )
                        },
                    required = listOf("allowedPackages", "sinceEpochMillis", "untilEpochMillis"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_QUERIED, ST_PERMISSION_MISSING),
                                    "queried, or permission-missing (Notification Listener not enabled).",
                                ),
                            )
                            put("count", integerSchema("Number of entries returned."))
                            put(
                                "excludedCount",
                                integerSchema("In-window notifications filtered out by the allowlist."),
                            )
                            put(
                                "entries",
                                notificationsCalendarToolsArraySchema(
                                    notificationsCalendarToolsNotificationEntrySchema(),
                                    "Bounded notification entries, newest first.",
                                ),
                            )
                            put("reason", stringSchema(128, "permission-missing on a refusal; empty on a query."))
                        },
                    required = listOf("status", "count", "excludedCount", "entries", "reason"),
                ),
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L1,
            timeout = 30.seconds,
            maxOutputBytes = 32_768,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: NotificationsBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount", "LongMethod")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val arr =
                    call.args["allowedPackages"] as? JsonArray
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'allowedPackages' must be an array",
                        )
                val packages = mutableListOf<String>()
                for (el in arr) {
                    val s =
                        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
                            ?: return ToolExecutorResult.Failed(
                                "invalid 'notifications.query' arguments: 'allowedPackages' entries must be strings",
                            )
                    if (s.isBlank() || s.length > MAX_PACKAGE_LEN) {
                        return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: each package must be 1..$MAX_PACKAGE_LEN",
                        )
                    }
                    packages.add(s)
                }
                if (packages.isEmpty() || packages.size > MAX_ALLOWED_PACKAGES) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: 'allowedPackages' must have " +
                            "1..$MAX_ALLOWED_PACKAGES entries",
                    )
                }
                val since =
                    notificationsCalendarToolsIntArg(call.args, "sinceEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'sinceEpochMillis' must be an integer",
                        )
                val until =
                    notificationsCalendarToolsIntArg(call.args, "untilEpochMillis")
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'notifications.query' arguments: 'untilEpochMillis' must be an integer",
                        )
                if (until <= since) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: 'untilEpochMillis' must be after 'sinceEpochMillis'",
                    )
                }
                if (until - since > MAX_NOTIFICATION_WINDOW_MS) {
                    return ToolExecutorResult.Failed(
                        "invalid 'notifications.query' arguments: the time window may be at most 24h",
                    )
                }
                val out = bridge.query(NotificationQueryRequest(packages, since, until))
                val status =
                    when (out.status) {
                        NotificationQueryStatus.QUERIED -> ST_QUERIED
                        NotificationQueryStatus.PERMISSION_MISSING -> ST_PERMISSION_MISSING
                        NotificationQueryStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("count", JsonPrimitive(out.entries.size))
                        put("excludedCount", JsonPrimitive(out.excludedCount))
                        put(
                            "entries",
                            JsonArray(
                                out.entries.map { e ->
                                    buildJsonObject {
                                        put("packageName", JsonPrimitive(e.packageName))
                                        put("title", JsonPrimitive(e.title))
                                        put("text", JsonPrimitive(e.text))
                                        put("postedEpochMillis", JsonPrimitive(e.postedEpochMillis))
                                    }
                                },
                            ),
                        )
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: NotificationsBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

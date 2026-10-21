package com.helix.app.tool

import com.helix.core.model.ToolName
import com.helix.tools.android.AndroidShareTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID

/** Closed native navigation requests, never arbitrary routes, credentials or settings changes. */
internal object DevicePageTool {
    const val NAME = "device.open_page"
    val routes =
        mapOf(
            "home" to "sessions",
            "settings" to "settings",
            "permissions" to "settings/permissions",
            "session" to "conversation/settings",
            "account" to "settings",
            "models" to "models",
            "memoryLibrary" to "knowledge",
            "reminders" to "reminders",
            "tasks" to "tasks",
            "plugins" to "extensions",
        )

    data class Request(
        val id: String,
        val route: String,
    )

    val pending = MutableStateFlow<Request?>(null)

    @Suppress("LongMethod") // Explicit closed route and outcome schemas, without execution logic.
    fun descriptor() =
        AndroidShareTool.descriptor().copy(
            name = ToolName(NAME),
            description =
                "Request navigation to a Helix native page: home, settings, permissions, session, " +
                    "account, models, memoryLibrary, " +
                    "reminders, tasks or plugins. navigation-requested means queued for the visible app, not proof " +
                    "of rendering. account opens the account section in Settings. " +
                    "Opening a page never changes settings, grants permissions or authorizes login.",
            inputSchema =
                Json.parseToJsonElement(
                    """
            {
              "type": "object",
              "properties": {
                "route": {
                  "type": "string",
                  "enum": [
                    "home",
                    "settings",
                    "permissions",
                    "session",
                    "account",
                    "models",
                    "memoryLibrary",
                    "reminders",
                    "tasks",
                    "plugins"
                  ]
                }
              },
              "required": [
                "route"
              ],
              "additionalProperties": false
            }
            """,
                ) as JsonObject,
            outputSchema =
                Json.parseToJsonElement(
                    """
            {
              "type": "object",
              "properties": {
                "status": {
                  "type": "string",
                  "enum": [
                    "navigation-requested"
                  ]
                },
                "route": {
                  "type": "string",
                  "maxLength": 32
                }
              },
              "required": [
                "status",
                "route"
              ],
              "additionalProperties": false
            }
            """,
                ) as JsonObject,
        )

    fun executor() =
        object : ToolExecutor {
            @Suppress("ReturnCount") // Cancellation and unsupported routes must not publish navigation.
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val route = (call.args["route"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val destination = routes[route] ?: return ToolExecutorResult.Failed("Unsupported Helix page")
                pending.value = Request(UUID.randomUUID().toString(), destination)
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive("navigation-requested"))
                        put("route", JsonPrimitive(route))
                    },
                )
            }
        }

    fun register(registry: ToolRegistry) {
        registry.register(descriptor(), executor())
    }
}

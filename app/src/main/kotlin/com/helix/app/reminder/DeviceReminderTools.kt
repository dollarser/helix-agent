package com.helix.app.reminder

import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.storage.reminder.LocalReminder
import com.helix.tools.android.AndroidShareTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.OffsetDateTime
import java.util.UUID

/** Personal notification reminders, explicitly not ringing alarms in another Clock app. */
internal object DeviceReminderTools {
    const val CREATE = "device.create_reminder"
    const val LIST = "device.list_reminders"
    const val CANCEL = "device.cancel_reminder"
    private const val MAX_LIST = 50

    private fun schema(
        properties: String,
        required: String,
    ) = Json.parseToJsonElement(
        """{"type":"object","properties":{$properties},"required":[$required],"additionalProperties":false}""",
    ) as JsonObject

    @Suppress("LongMethod") // Closed input/output schemas are kept together for contract review.
    fun descriptor(name: String) =
        AndroidShareTool.descriptor().copy(
            name = ToolName(name),
            description =
                when (name) {
                    CREATE -> {
                        "Create a Helix notification reminder using [time] " +
                            "(ISO 8601 with UTC offset) and [label]. Not a ringing Clock alarm. " +
                            "Requires an unambiguous future time. Scheduling may be approximate " +
                            "or blocked by notification permissions; " +
                            "saved does not mean delivered. Do not create from ASR partial or " +
                            "unconfirmed external content."
                    }

                    LIST -> {
                        "List up to 50 active Helix notification reminders. " +
                            "Returns exact id, revision, time and label. " +
                            "Call before device.cancel_reminder; never guess IDs from time or label. " +
                            "Does not list alarms managed by other Clock apps."
                    }

                    else -> {
                        "Cancel a Helix reminder returned by device.list_reminders in this session. " +
                            "Pass its exact [id] and [revision]. Changed or inactive reminders " +
                            "are refused; list again. Cancels the whole reminder. " +
                            "Cannot cancel another Clock app's alarm."
                    }
                },
            operationClass = if (name == LIST) ToolOperationClass.READ_ONLY else ToolOperationClass.LOCAL_MUTATION,
            inputSchema =
                when (name) {
                    CREATE -> {
                        schema(
                            """
              "time": {
                "type": "string",
                "maxLength": 80
              },
              "label": {
                "type": "string",
                "maxLength": 160
              }
            """,
                            "\"time\",\"label\"",
                        )
                    }

                    LIST -> {
                        schema("", "")
                    }

                    else -> {
                        schema(
                            """
              "id": {
                "type": "string",
                "maxLength": 64
              },
              "revision": {
                "type": "integer"
              }
            """,
                            "\"id\",\"revision\"",
                        )
                    }
                },
            outputSchema =
                schema(
                    """
              "status": {
                "type": "string",
                "maxLength": 40
              },
              "items": {
                "type": "array",
                "maxItems": 50,
                "items": {
                  "type": "object",
                  "properties": {
                    "id": {
                      "type": "string",
                      "maxLength": 64
                    },
                    "revision": {
                      "type": "integer"
                    },
                    "time": {
                      "type": "string",
                      "maxLength": 80
                    },
                    "label": {
                      "type": "string",
                      "maxLength": 160
                    },
                    "scheduling": {
                      "type": "string",
                      "maxLength": 40
                    }
                  },
                  "required": [
                    "id",
                    "revision",
                    "time",
                    "label",
                    "scheduling"
                  ],
                  "additionalProperties": false
                }
              },
              "hasMore": {
                "type": "boolean"
              }
            """,
                    "\"status\",\"items\",\"hasMore\"",
                ),
            maxOutputBytes = 65536,
        )

    fun register(
        registry: ToolRegistry,
        backend: Backend,
    ) {
        val access = Access(backend)
        listOf(CREATE, LIST, CANCEL).forEach { name ->
            registry.register(
                descriptor(name),
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall) = access.execute(name, call)
                },
            )
        }
    }

    interface Backend {
        suspend fun list(): List<LocalReminder>

        suspend fun save(reminder: LocalReminder): LocalReminder

        suspend fun cancel(
            id: String,
            revision: Long,
        ): Boolean
    }

    class Access(
        private val backend: Backend,
    ) {
        // Freshness admission only. Durable revision is checked again under the service mutation lock.
        private val listed = java.util.concurrent.ConcurrentHashMap<String, Map<String, Long>>()

        @Suppress("ReturnCount", "TooGenericExceptionCaught") // Never turn exceptions into success.
        fun execute(
            name: String,
            call: ExecutableToolCall,
        ): ToolExecutorResult {
            if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
            val session = call.sessionId ?: return ToolExecutorResult.Failed("Missing trusted session")
            return try {
                runBlocking(Dispatchers.IO) {
                    when (name) {
                        LIST -> list(session)
                        CREATE -> create(session, call)
                        CANCEL -> cancel(session, call)
                        else -> ToolExecutorResult.Failed("Unknown device reminder operation")
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ToolExecutorResult.Failed("Reminder operation failed; inspect reminders before retrying")
            }
        }

        private suspend fun list(session: String): ToolExecutorResult {
            val all =
                backend
                    .list()
                    .filter { it.state == "ACTIVE" && it.localTime.isNotBlank() }
                    .sortedBy { it.nextAt ?: Long.MAX_VALUE }
            val rows = all.take(MAX_LIST)
            if (listed.size >= 128) listed.clear()
            listed[session] = rows.associate { it.id to it.revision }
            return result("listed", rows, all.size > MAX_LIST)
        }

        @Suppress("ReturnCount") // Reject incomplete fields before persistence.
        private suspend fun create(session: String, call: ExecutableToolCall): ToolExecutorResult {
            val time =
                string(call, "time")?.let(OffsetDateTime::parse)
                    ?: return ToolExecutorResult.Failed("Invalid time")
            val label =
                string(call, "label")?.takeIf { it.isNotBlank() && it.length <= 160 }
                    ?: return ToolExecutorResult.Failed("Invalid label")
            val id = UUID.nameUUIDFromBytes("$session:${call.turnId}:${call.toolCallId}".toByteArray()).toString()
            if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
            val item =
                backend.save(
                    LocalReminder(
                        id,
                        label,
                        "",
                        time.toLocalDateTime().toString(),
                        time.offset.id,
                        "ONCE",
                        false,
                        null,
                        revision = 0,
                    ),
                )
            return result("saved", listOf(item), false)
        }

        @Suppress("ReturnCount") // Revalidate exact list identity and cancellation before mutation.
        private suspend fun cancel(session: String, call: ExecutableToolCall): ToolExecutorResult {
            val id = string(call, "id")
            val revision = (call.args["revision"] as? JsonPrimitive)?.content?.toLongOrNull()
            if (id == null || revision == null || listed[session]?.get(id) != revision) {
                return ToolExecutorResult.Failed("List reminders first and use the exact id/revision")
            }
            if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
            if (!backend.cancel(id, revision)) return ToolExecutorResult.Failed("Reminder changed; list again")
            listed.remove(session)
            return result("cancelled", emptyList(), false)
        }

        private fun string(
            call: ExecutableToolCall,
            key: String,
        ) = (call.args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    private fun result(
        status: String,
        items: List<LocalReminder>,
        more: Boolean,
    ) = ToolExecutorResult.Completed(
        buildJsonObject {
            put("status", JsonPrimitive(status))
            put("hasMore", JsonPrimitive(more))
            put(
                "items",
                JsonArray(
                    items.map { item ->
                        buildJsonObject {
                            put("id", JsonPrimitive(item.id))
                            put("revision", JsonPrimitive(item.revision))
                            put("time", JsonPrimitive("${item.localTime}[${item.zone}]"))
                            put("label", JsonPrimitive(item.title))
                            put("scheduling", JsonPrimitive(item.scheduling))
                        }
                    },
                ),
            )
        },
    )
}

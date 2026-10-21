package com.helix.app.memory

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object MemoryTools {
    val reads = setOf("memory.list", "memory.search", "memory.read")
    val writes = setOf("memory.write", "memory.edit", "memory.delete")

    fun register(
        registry: ToolRegistry,
        service: MemoryService,
    ) {
        (reads + writes).forEach { name ->
            val writing = name in writes
            val descriptor =
                ToolDescriptor(
                    name = ToolName(name),
                    version = ToolVersion(1),
                    description = MemoryToolSchemas.description(name),
                    inputSchema = schema(name),
                    outputSchema = MemoryToolSchemas.output(name),
                    operationClass = if (writing) ToolOperationClass.LOCAL_MUTATION else ToolOperationClass.READ_ONLY,
                    timeout = 5.seconds,
                    maxOutputBytes = 262_144,
                    requiredCapabilities = emptySet(),
                    idempotency = if (writing) Idempotency.NON_IDEMPOTENT else Idempotency.IDEMPOTENT,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    origin = ToolOrigin.BuiltInOrigin,
                )

            registry.register(
                descriptor,
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult = execute(service, call)
                },
            )
        }
    }

    private fun schema(name: String): JsonObject {
        val fields =
            linkedMapOf(
                "scope" to
                    Json
                        .parseToJsonElement(
                            """{"type":"string","enum":["global","project"],
            "description":"Memory scope; project requires the trusted current project identity, never a path."}""",
                        ).jsonObject,
            )
        if (name != "memory.list" && name != "memory.search") {
            fields["path"] =
                MemoryToolSchemas.text(67, "Logical Markdown memory path returned by list/search; not a device path.")
        }
        if (name == "memory.search") fields["query"] = MemoryToolSchemas.text(256, "Search terms for scoped memory.")
        if (name in writes) {
            fields["expectedHash"] =
                buildJsonObject {
                    put("type", "string")
                    put("pattern", if (name == "memory.write") "^(new|[0-9a-f]{64})$" else "^[0-9a-f]{64}$")
                    put(
                        "description",
                        "Exact SHA-256 from read/list/search; 'new' is only valid when creating with memory.write.",
                    )
                }
        }
        if (name == "memory.write") {
            fields["type"] =
                Json
                    .parseToJsonElement(
                        """{"type":"string","enum":["user","feedback","project","reference"]}""",
                    ).jsonObject
            fields["source"] = MemoryToolSchemas.text(256, "Human-readable provenance of the memory, not authority.")
            fields["body"] =
                MemoryToolSchemas.text(32000, "Body text without frontmatter; the host generates metadata.")
        }
        if (name == "memory.edit") {
            fields["old"] = MemoryToolSchemas.text(32000, "Exact nonempty text occurring once in the stored Markdown.")
            fields["replacement"] = MemoryToolSchemas.text(32000, "Replacement text; empty deletes the matched text.")
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(fields))
            put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun execute(
        service: MemoryService,
        call: ExecutableToolCall,
    ): ToolExecutorResult {
        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
        if (java.time.Instant
                .now()
                .isAfter(call.deadline)
        ) {
            return ToolExecutorResult.TimedOut
        }
        val writing = call.toolName in writes
        return try {
            val scope =
                service.scopeForTool(
                    call.args
                        .getValue("scope")
                        .jsonPrimitive.content,
                    call.sessionId,
                    call.turnId,
                    call.toolCallId,
                )
            service.requireModelAccess(scope, writing)
            val result = result(service, call, scope)
            ToolExecutorResult.Completed(result)
        } catch (
            cancelled: kotlinx.coroutines.CancellationException,
        ) {
            throw cancelled
        } catch (
            _: IllegalArgumentException,
        ) {
            ToolExecutorResult.Failed("Invalid memory or conflict. Reload before retry.", sideEffectFree = !writing)
        } catch (
            _: IllegalStateException,
        ) {
            ToolExecutorResult.Failed(
                "Memory disabled or project unavailable. Check settings.",
                sideEffectFree = !writing,
            )
        } catch (
            _: java.io.IOException,
        ) {
            ToolExecutorResult.Failed(
                "Memory storage unavailable. Read before retry.",
                sideEffectFree = !writing,
                requiresReview = writing,
            )
        }
    }

    private fun result(
        service: MemoryService,
        call: ExecutableToolCall,
        scope: com.helix.core.workspace.memory.MemoryScope,
    ): JsonObject {
        val arg = { key: String ->
            call.args
                .getValue(key)
                .jsonPrimitive.content
        }
        return when (call.toolName) {
            "memory.list", "memory.search" -> {
                val entries =
                    if (call.toolName ==
                        "memory.list"
                    ) {
                        service.list(scope)
                    } else {
                        service.search(scope, arg("query"))
                    }
                entryList(entries)
            }

            "memory.read" -> {
                service.read(scope, arg("path")).let {
                    buildJsonObject {
                        put("markdown", it.markdown)
                        put("hash", it.hash)
                        put("trust", "untrusted")
                    }
                }
            }

            "memory.write" -> {
                service
                    .save(
                        scope,
                        arg("path"),
                        service.newMarkdown(arg("type"), arg("source"), arg("body")),
                        arg("expectedHash"),
                    ).let { buildJsonObject { put("hash", it.hash) } }
            }

            "memory.edit" -> {
                service
                    .edit(scope, arg("path"), arg("expectedHash"), arg("old"), arg("replacement"))
                    .let { buildJsonObject { put("hash", it.hash) } }
            }

            "memory.delete" -> {
                service.delete(scope, arg("path"), arg("expectedHash"))
                buildJsonObject { put("deleted", true) }
            }

            else -> {
                error("MEMORY_UNKNOWN_OPERATION")
            }
        }
    }

    private fun entryList(entries: List<com.helix.core.workspace.memory.MemoryEntry>): JsonObject =
        buildJsonObject {
            put(
                "entries",
                JsonArray(
                    entries.map { entry ->
                        buildJsonObject {
                            put("path", entry.path)
                            put("hash", entry.hash)
                            put("updatedAt", entry.updatedAt)
                        }
                    },
                ),
            )
        }
}

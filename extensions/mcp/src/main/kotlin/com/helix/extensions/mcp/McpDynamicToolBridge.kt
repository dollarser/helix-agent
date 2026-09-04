package com.helix.extensions.mcp

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.McpServerId
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.DataSensitivity
import com.helix.core.policy.EgressRequest
import com.helix.core.policy.EgressTarget
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.McpToolSource
import com.helix.tools.framework.McpToolSpec
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

fun interface McpToolCaller {
    /** Executes only after Dispatcher policy/approval; implementations must honor deadline/cancel. */
    fun call(
        call: ExecutableToolCall,
        serverToolName: String,
    ): McpToolResult
}

data class McpSessionSendSummary(
    val serverId: McpServerId,
    val origin: String,
    val dataSensitivity: DataSensitivity,
    val argumentBytes: Int,
    val argumentHash: String,
)

data class McpToolDispatchFacts(
    val egress: EgressRequest,
    val sendSummary: McpSessionSendSummary,
    val originSeenInSession: Boolean,
    val sourceBindingChanged: Boolean,
    val checkpointRequired: Boolean,
)

class McpSessionCheckpointTracker {
    private val lock = Any()
    private var lastEndpoint: String? = null
    private val schemaByTool = mutableMapOf<String, String>()
    private val sensitivityByTool = mutableMapOf<String, DataSensitivity>()

    fun evaluate(
        endpoint: String,
        toolName: String,
        schemaHash: String,
        sensitivity: DataSensitivity,
    ): McpCheckpointChange =
        synchronized(lock) {
            val previousEndpoint = lastEndpoint
            val previousSchema = schemaByTool[toolName]
            val previousSensitivity = sensitivityByTool[toolName]
            val change =
                McpCheckpointChange(
                    endpointChanged = previousEndpoint != null && previousEndpoint != endpoint,
                    schemaChanged = previousSchema != null && previousSchema != schemaHash,
                    sensitivityChanged = previousSensitivity != null && previousSensitivity != sensitivity,
                    firstSend = previousEndpoint == null || previousSchema == null || previousSensitivity == null,
                )
            lastEndpoint = endpoint
            schemaByTool[toolName] = schemaHash
            sensitivityByTool[toolName] = sensitivity
            change
        }
}

data class McpCheckpointChange(
    val endpointChanged: Boolean,
    val schemaChanged: Boolean,
    val sensitivityChanged: Boolean,
    val firstSend: Boolean,
) {
    val required: Boolean = endpointChanged || schemaChanged || sensitivityChanged || firstSend
}

class McpDynamicToolBridge(
    private val config: McpServerConfig,
    private val identity: McpServerIdentity,
    metadata: List<McpToolMetadata>,
    caller: McpToolCaller,
) {
    init {
        require(config.enabled) { "MCP tools can only be registered from a user-enabled server" }
    }

    private val source =
        McpToolSource(
            serverId = config.id.value,
            protocolVersion = identity.negotiatedProtocolVersion,
            specs = metadata.map { tool -> tool.toSpec() },
        )
    private val descriptors = source.load()
    private val executors =
        descriptors.zip(metadata).associate { (descriptor, tool) ->
            descriptor to McpToolExecutor(tool.name, caller)
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) {
        registry.replaceMcpServer(config.id.value, descriptors)
        implementations.replaceMcpServer(config.id.value, executors.entries.map { it.key to it.value })
    }

    fun descriptors(): List<ToolDescriptor> = descriptors

    fun dispatchFacts(
        descriptor: ToolDescriptor,
        arguments: JsonObject,
        sensitivity: DataSensitivity,
        tracker: McpSessionCheckpointTracker,
    ): McpToolDispatchFacts {
        val origin = descriptor.origin as? com.helix.tools.framework.ToolOrigin.McpOrigin
        require(origin?.serverId == config.id.value) { "descriptor does not belong to this MCP server" }
        val canonicalArguments = arguments.canonicalJson()
        val checkpoint =
            tracker.evaluate(
                endpoint = config.endpoint.full,
                toolName = descriptor.name.value,
                schemaHash = origin.sourceSchemaHash,
                sensitivity = sensitivity,
            )
        return McpToolDispatchFacts(
            egress = EgressRequest(EgressTarget.Mcp(config.id), config.endpoint, sensitivity),
            sendSummary =
                McpSessionSendSummary(
                    serverId = config.id,
                    origin = config.endpoint.origin,
                    dataSensitivity = sensitivity,
                    argumentBytes = canonicalArguments.toByteArray(Charsets.UTF_8).size,
                    argumentHash = canonicalArguments.sha256(),
                ),
            originSeenInSession = !checkpoint.firstSend && !checkpoint.endpointChanged,
            sourceBindingChanged = checkpoint.schemaChanged,
            checkpointRequired = checkpoint.required,
        )
    }

    private fun McpToolMetadata.toSpec(): McpToolSpec =
        McpToolSpec(
            serverToolName = name,
            version = ToolVersion(1),
            description = description?.take(ToolDescriptor.MAX_DESCRIPTION_LENGTH) ?: "MCP tool $name",
            inputSchema = inputSchema,
            outputSchema = MCP_RESULT_SCHEMA,
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L1,
            timeout = 60.seconds,
            maxOutputBytes = 2L * 1024 * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            sourceSchemaHash = schemaHash,
            serverProvidedHints = serverProvidedHints,
        )
}

private class McpToolExecutor(
    private val serverToolName: String,
    private val caller: McpToolCaller,
) : ToolExecutor {
    @Suppress("ReturnCount") // cancellation and deadline are terminal pre-network exits
    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
        if (java.time.Instant.now() >= call.deadline) return ToolExecutorResult.TimedOut
        return runCatching { caller.call(call, serverToolName) }
            .fold(
                onSuccess = { result ->
                    ToolExecutorResult.Completed(
                        output = result.toJson(),
                        auditDetail =
                            buildJsonObject {
                                put("mcpServerResultError", result.isError)
                                put("mcpResultBlocks", result.blocks.size)
                                put("mcpStructuredResult", result.structuredContent != null)
                            },
                    )
                },
                onFailure = { failure ->
                    when (failure) {
                        is TimeoutCancellationException -> {
                            ToolExecutorResult.TimedOut
                        }

                        is CancellationException -> {
                            ToolExecutorResult.Cancelled
                        }

                        else -> {
                            ToolExecutorResult.Failed(
                                detail = "MCP tool failed: ${failure::class.simpleName}",
                                sideEffectFree = false,
                            )
                        }
                    }
                },
            )
    }
}

private fun McpToolResult.toJson(): JsonObject =
    buildJsonObject {
        put("isError", isError)
        put("blocks", buildJsonArray { blocks.forEach { add(it.toJson()) } })
        structuredContent?.let { put("structuredContent", it) }
    }

private fun McpResultBlock.toJson(): JsonObject =
    buildJsonObject {
        when (this@toJson) {
            is McpResultBlock.Text -> {
                put("kind", "text")
                put("text", text)
            }

            is McpResultBlock.Image -> {
                put("kind", "image")
                put("mimeType", mimeType)
                put("data", base64Data)
            }

            is McpResultBlock.Audio -> {
                put("kind", "audio")
                put("mimeType", mimeType)
                put("data", base64Data)
            }

            is McpResultBlock.ResourceLink -> {
                put("kind", "resource_link")
                put("uri", uri)
                put("name", name)
                title?.let { put("title", it) }
                description?.let { put("description", it) }
                mimeType?.let { put("mimeType", it) }
                size?.let { put("size", it) }
            }

            is McpResultBlock.EmbeddedText -> {
                put("kind", "embedded_text")
                put("uri", uri)
                mimeType?.let { put("mimeType", it) }
                put("text", text)
            }

            is McpResultBlock.EmbeddedBlob -> {
                put("kind", "embedded_blob")
                put("uri", uri)
                mimeType?.let { put("mimeType", it) }
                put("data", base64Data)
            }
        }
    }

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val MCP_RESULT_SCHEMA =
    buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("isError", buildJsonObject { put("type", "boolean") })
                put(
                    "blocks",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put("additionalProperties", true)
                            },
                        )
                    },
                )
                put(
                    "structuredContent",
                    buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", true)
                    },
                )
            },
        )
        put(
            "required",
            buildJsonArray {
                add(JsonPrimitive("isError"))
                add(JsonPrimitive("blocks"))
            },
        )
        put("additionalProperties", false)
    }

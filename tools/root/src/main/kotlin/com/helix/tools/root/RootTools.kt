package com.helix.tools.root

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDiscoveryHints
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolJsonNumbers
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/** HXA-095 model-visible, high-level Root tools. No arbitrary command descriptor exists here. */
@Suppress("TooManyFunctions") // schema helpers stay beside the five versioned contracts
class RootTools(
    private val port: RootOperationPort,
    private val sessions: RootSessionManager,
) {
    fun descriptors(): List<ToolDescriptor> =
        listOf(
            descriptor(STATUS, emptySet(), emptyObjectSchema(), statusOutputSchema()),
            descriptor(
                FILE_READ,
                rootCapability(),
                fileReadInputSchema(),
                fileReadOutputSchema(),
            ),
            descriptor(
                PACKAGE_INFO,
                rootCapability(),
                packageInputSchema(),
                packageOutputSchema(),
            ),
            descriptor(
                PROCESS_LIST,
                rootCapability(),
                processInputSchema(),
                processOutputSchema(),
            ),
            descriptor(
                LOG_READ,
                rootCapability(),
                logInputSchema(),
                logOutputSchema(),
            ),
        )

    fun register(registry: ToolRegistry) {
        descriptors().forEach { descriptor ->

            registry.register(descriptor, executor(descriptor.name.value))
        }
    }

    fun executor(name: String): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult = this@RootTools.execute(name, call)
        }

    @Suppress("ReturnCount") // cancel/unknown/failure are distinct fail-closed exits
    private fun execute(
        name: String,
        call: ExecutableToolCall,
    ): ToolExecutorResult {
        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
        if (name == STATUS) return ToolExecutorResult.Completed(statusJson())
        return try {
            sessions.requireActive()
            val result =
                when (name) {
                    FILE_READ -> {
                        executeFileRead(call.args)
                    }

                    PACKAGE_INFO -> {
                        port.execute(RootOperationRequest.PackageInfo(requiredString(call.args, "packageName")))
                    }

                    PROCESS_LIST -> {
                        port.execute(RootOperationRequest.ProcessList(optionalInt(call.args, "limit", 100)))
                    }

                    LOG_READ -> {
                        port.execute(
                            RootOperationRequest.LogRead(
                                optionalInt(call.args, "maxLines", 200),
                                optionalString(call.args, "minPriority", "I"),
                            ),
                        )
                    }

                    else -> {
                        return ToolExecutorResult.Failed("ROOT_TOOL_UNKNOWN", sideEffectFree = true)
                    }
                }
            if (result is RootOperationResult.Failed) {
                ToolExecutorResult.Failed(result.code, sideEffectFree = true)
            } else {
                sessions.recordSuccessfulActivity()
                ToolExecutorResult.Completed(toJson(name, call.args, result))
            }
        } catch (error: IllegalArgumentException) {
            ToolExecutorResult.Failed(error.message ?: "ROOT_ARGUMENT_INVALID", sideEffectFree = true)
        } catch (error: IllegalStateException) {
            ToolExecutorResult.Failed(error.message ?: "ROOT_SESSION_INACTIVE", sideEffectFree = true)
        }
    }

    private fun executeFileRead(args: JsonObject): RootOperationResult {
        val resolved = sessions.resolve(requiredString(args, "scopeId"), requiredString(args, "relativePath"))
        return port.execute(
            RootOperationRequest.FileRead(
                scopeRoot = resolved.root,
                path = resolved.path,
                offset = optionalLong(args, "offset", 0L),
                maxBytes = optionalInt(args, "maxBytes", DEFAULT_FILE_BYTES),
            ),
        )
    }

    private fun statusJson(): JsonObject {
        val access = port.status()
        val session = sessions.status()
        return buildJsonObject {
            put("grant", JsonPrimitive(access.grant.name.lowercase()))
            put("service", JsonPrimitive(access.service.name.lowercase()))
            put("session", JsonPrimitive(session.state.name.lowercase()))
            put("expiresAtEpochSeconds", JsonPrimitive(session.scope?.expiresAt?.epochSecond ?: 0L))
            put("highLevelToolsOnly", JsonPrimitive(session.scope?.highLevelToolsOnly ?: true))
        }
    }

    private fun toJson(
        name: String,
        args: JsonObject,
        result: RootOperationResult,
    ): JsonObject =
        when (result) {
            is RootOperationResult.File -> {
                val bytes = result.chunk.bytes
                val utf8 = bytes.toString(StandardCharsets.UTF_8)
                val roundTrips = utf8.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)
                buildJsonObject {
                    put("scopeId", JsonPrimitive(requiredString(args, "scopeId")))
                    put("relativePath", JsonPrimitive(requiredString(args, "relativePath")))
                    put("offset", JsonPrimitive(result.chunk.offset))
                    put("sizeBytes", JsonPrimitive(result.chunk.sizeBytes))
                    put("windowLength", JsonPrimitive(bytes.size))
                    put("encoding", JsonPrimitive(if (roundTrips) "utf-8" else "base64"))
                    put("content", JsonPrimitive(if (roundTrips) utf8 else Base64.getEncoder().encodeToString(bytes)))
                    put("eof", JsonPrimitive(result.chunk.eof))
                }
            }

            is RootOperationResult.Package -> {
                buildJsonObject {
                    put("packageName", JsonPrimitive(result.record.packageName))
                    put("uid", JsonPrimitive(result.record.uid))
                    put("sourceDir", JsonPrimitive(result.record.sourceDir))
                    put("versionName", JsonPrimitive(result.record.versionName ?: ""))
                }
            }

            is RootOperationResult.Processes -> {
                buildJsonObject {
                    put(
                        "processes",
                        JsonArray(
                            result.records.map { record ->
                                buildJsonObject {
                                    put("pid", JsonPrimitive(record.pid))
                                    put("uid", JsonPrimitive(record.uid))
                                    put("name", JsonPrimitive(record.name))
                                }
                            },
                        ),
                    )
                    put("count", JsonPrimitive(result.records.size))
                }
            }

            is RootOperationResult.Logs -> {
                buildJsonObject {
                    put("lines", JsonArray(result.lines.map { JsonPrimitive(redactLog(it)) }))
                    put("count", JsonPrimitive(result.lines.size))
                }
            }

            is RootOperationResult.Failed -> {
                error("failure handled before JSON conversion")
            }
        }

    private fun descriptor(
        name: String,
        capabilities: Set<Capability>,
        input: JsonObject,
        output: JsonObject,
    ) = ToolDescriptor(
        name = ToolName(name),
        version = ToolVersion(1),
        description = toolDescription(name),
        inputSchema = input,
        outputSchema = output,
        operationClass = ToolOperationClass.READ_ONLY,
        timeout = 30.seconds,
        maxOutputBytes = MAX_OUTPUT_BYTES,
        requiredCapabilities = capabilities,
        idempotency = Idempotency.IDEMPOTENT,
        executionTarget = ExecutionTargetType.LOCAL_ROOT,
        origin = ToolOrigin.BuiltInOrigin,
        discovery = ToolDiscoveryHints(category = "root"),
    )

    private fun toolDescription(name: String): String =
        when (name) {
            STATUS -> {
                "Inspect Root grant, service and session availability without activating a Root session."
            }

            FILE_READ -> {
                "Read a byte window of a file within an already granted Root scope. " +
                    "Prefer read for ordinary files; this tool requires a scopeId and relativePath. " +
                    "Returns UTF-8 or base64 content; continue at offset + windowLength until eof."
            }

            PACKAGE_INFO -> {
                "Inspect an installed Android package by package name using the active Root session. " +
                    "Returns UID, installation path and version; does not launch or modify the app."
            }

            PROCESS_LIST -> {
                "List a bounded snapshot of running Android processes with PID, UID and name. " +
                    "Requires an active Root session; does not signal or stop processes."
            }

            LOG_READ -> {
                "Read recent Android logcat lines at or above a severity using the active Root session. " +
                    "Returns a bounded diagnostic snapshot, not a live stream."
            }

            else -> {
                error("Unknown Root tool: $name")
            }
        }

    private fun described(
        schema: JsonObject,
        description: String,
    ): JsonObject = JsonObject(schema + ("description" to JsonPrimitive(description)))

    private fun emptyObjectSchema() = objectSchema(emptyMap(), emptyList())

    private fun statusOutputSchema() =
        objectSchema(
            mapOf(
                "grant" to stringSchema(16),
                "service" to stringSchema(16),
                "session" to stringSchema(16),
                "expiresAtEpochSeconds" to integerSchema(0),
                "highLevelToolsOnly" to booleanSchema(),
            ),
            listOf("grant", "service", "session", "expiresAtEpochSeconds", "highLevelToolsOnly"),
        )

    private fun fileReadInputSchema() =
        objectSchema(
            mapOf(
                "scopeId" to
                    described(stringSchema(64), "Exact identifier of an existing user-granted Root file scope."),
                "relativePath" to
                    described(stringSchema(1024), "File path relative to that scope, not an absolute path."),
                "offset" to
                    described(
                        JsonObject(integerSchema(0) + ("maximum" to JsonPrimitive(Long.MAX_VALUE))),
                        "Zero-based byte offset; omit for 0. Continue at previous offset + windowLength.",
                    ),
                "maxBytes" to described(integerSchema(1, MAX_FILE_BYTES), "Byte window limit; defaults to 65536."),
            ),
            listOf("scopeId", "relativePath"),
        )

    private fun fileReadOutputSchema() =
        objectSchema(
            mapOf(
                "scopeId" to stringSchema(64),
                "relativePath" to stringSchema(1024),
                "offset" to integerSchema(0),
                "sizeBytes" to integerSchema(0),
                "windowLength" to integerSchema(0, MAX_FILE_BYTES),
                "encoding" to stringSchema(16),
                "content" to stringSchema(MAX_OUTPUT_BYTES.toInt()),
                "eof" to booleanSchema(),
            ),
            listOf("scopeId", "relativePath", "offset", "sizeBytes", "windowLength", "encoding", "content", "eof"),
        )

    private fun packageInputSchema() =
        objectSchema(
            mapOf("packageName" to described(stringSchema(255), "Exact Android package identifier, not an app label.")),
            listOf("packageName"),
        )

    private fun packageOutputSchema() =
        objectSchema(
            mapOf(
                "packageName" to stringSchema(255),
                "uid" to integerSchema(0),
                "sourceDir" to stringSchema(2048),
                "versionName" to stringSchema(256),
            ),
            listOf("packageName", "uid", "sourceDir", "versionName"),
        )

    private fun processInputSchema() =
        objectSchema(
            mapOf("limit" to described(integerSchema(1, MAX_PROCESSES), "Maximum process records; defaults to 100.")),
            emptyList(),
        )

    private fun processOutputSchema() =
        objectSchema(
            mapOf(
                "processes" to
                    arraySchema(
                        objectSchema(
                            mapOf(
                                "pid" to integerSchema(1),
                                "uid" to integerSchema(0),
                                "name" to stringSchema(256),
                            ),
                            listOf("pid", "uid", "name"),
                        ),
                        MAX_PROCESSES,
                    ),
                "count" to integerSchema(0, MAX_PROCESSES),
            ),
            listOf("processes", "count"),
        )

    private fun logInputSchema() =
        objectSchema(
            mapOf(
                "maxLines" to described(integerSchema(1, MAX_LOG_LINES), "Maximum recent log lines; defaults to 200."),
                "minPriority" to
                    described(
                        JsonObject(
                            stringSchema(1) +
                                ("enum" to JsonArray(listOf("V", "D", "I", "W", "E", "F", "S").map(::JsonPrimitive))),
                        ),
                        "Minimum logcat severity: verbose, debug, info, warning, " +
                            "error, fatal or silent. Defaults to I.",
                    ),
            ),
            emptyList(),
        )

    private fun logOutputSchema() =
        objectSchema(
            mapOf(
                "lines" to arraySchema(stringSchema(MAX_LOG_LINE_LENGTH), MAX_LOG_LINES),
                "count" to integerSchema(0, MAX_LOG_LINES),
            ),
            listOf("lines", "count"),
        )

    private fun objectSchema(
        properties: Map<String, JsonObject>,
        required: List<String>,
    ) = buildJsonObject {
        put("type", JsonPrimitive("object"))
        if (properties.isNotEmpty()) {
            put(
                "properties",
                buildJsonObject {
                    properties.forEach { (key, value) ->
                        put(key, value)
                    }
                },
            )
        }
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", JsonPrimitive(false))
    }

    private fun stringSchema(maxLength: Int) =
        buildJsonObject {
            put("type", JsonPrimitive("string"))
            put("maxLength", JsonPrimitive(maxLength))
        }

    private fun integerSchema(
        minimum: Int,
        maximum: Int? = null,
    ) = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("minimum", JsonPrimitive(minimum))
        maximum?.let { put("maximum", JsonPrimitive(it)) }
    }

    private fun booleanSchema() = buildJsonObject { put("type", JsonPrimitive("boolean")) }

    private fun arraySchema(
        items: JsonObject,
        maxItems: Int,
    ) = buildJsonObject {
        put("type", JsonPrimitive("array"))
        put("items", items)
        put("maxItems", JsonPrimitive(maxItems))
    }

    private fun requiredString(
        args: JsonObject,
        key: String,
    ): String = requireNotNull(args[key]?.jsonPrimitive?.contentOrNull) { "ROOT_ARGUMENT_MISSING_$key" }

    private fun optionalString(
        args: JsonObject,
        key: String,
        default: String,
    ) = args[key]?.jsonPrimitive?.contentOrNull ?: default

    private fun optionalInt(
        args: JsonObject,
        key: String,
        default: Int,
    ) = if (key !in
        args
    ) {
        default
    } else {
        requireNotNull(ToolJsonNumbers.intOrNull(args[key])) { "ROOT_ARGUMENT_INVALID_$key" }
    }

    private fun optionalLong(
        args: JsonObject,
        key: String,
        default: Long,
    ) = if (key !in
        args
    ) {
        default
    } else {
        requireNotNull(ToolJsonNumbers.longOrNull(args[key])) { "ROOT_ARGUMENT_INVALID_$key" }
    }

    private fun redactLog(line: String): String =
        line
            .take(MAX_LOG_LINE_LENGTH)
            .replace(SECRET_ASSIGNMENT, "$1=<redacted>")
            .replace(BEARER, "Bearer <redacted>")

    companion object {
        const val STATUS = "root_status"
        const val FILE_READ = "root_file_read"
        const val PACKAGE_INFO = "root_package_info"
        const val PROCESS_LIST = "root_process_list"
        const val LOG_READ = "root_log_read"
        const val DEFAULT_FILE_BYTES = 64 * 1024
        const val MAX_FILE_BYTES = 256 * 1024
        const val MAX_PROCESSES = 512
        const val MAX_LOG_LINES = 500

        // 500 UTF-16 Parcel strings at this cap stay comfortably below Binder's 1 MiB
        // transaction ceiling even before the framework applies its JSON output limit.
        const val MAX_LOG_LINE_LENGTH = 512
        const val MAX_OUTPUT_BYTES = 1024L * 1024L
        private val SECRET_ASSIGNMENT = Regex("(?i)(api[_-]?key|token|password|secret)\\s*[:=]\\s*[^\\s,;]+")
        private val BEARER = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")

        private fun rootCapability() = setOf(Capability.ROOT_SHELL)
    }
}

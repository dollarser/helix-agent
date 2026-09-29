package com.helix.tools.files

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolVisualPreparation
import com.helix.tools.framework.VisualPreparationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

/** Pixel observation, not textual/base64 file reading. All access still passes the Dispatcher. */
object ViewImageTool {
    const val NAME = "view_image"

    fun descriptor() =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(1),
            description =
                "Open an image from the authorized workspace for visual analysis. " +
                    "Use this for screenshots, photos and generated charts, not read/base64. " +
                    "Pixels join the tool observation after image validation and data disclosure.",
            inputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        putJsonObject("path") {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 4096)
                        }
                    }
                    putJsonArray("required") { add("path") }
                },
            outputSchema =
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", false)
                    putJsonObject("properties") {
                        listOf("status", "artifactId", "sha256", "mediaType").forEach { key ->
                            putJsonObject(key) { put("type", "string") }
                        }
                        listOf("width", "height", "sizeBytes").forEach { key ->
                            putJsonObject(key) {
                                put("type", "integer")
                                put("minimum", 1)
                            }
                        }
                    }
                    putJsonArray("required") {
                        listOf(
                            "status",
                            "artifactId",
                            "sha256",
                            "mediaType",
                            "width",
                            "height",
                            "sizeBytes",
                        ).forEach(::add)
                    }
                },
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(preparation: ToolVisualPreparation): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                return try {
                    val input = (call.args["path"] as? JsonPrimitive)?.takeIf { it.isString }
                    val path = requireNotNull(input?.content)
                    FileScopePath.fromModelReference(path)
                    val image = preparation.prepare(call, path, null)
                    if (call.cancel.isCancelled()) {
                        ToolExecutorResult.Cancelled
                    } else {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("status", "IMAGE_PREPARED")
                                put("artifactId", image.artifactId)
                                put("sha256", image.sha256)
                                put("mediaType", image.mediaType)
                                put("sizeBytes", image.sizeBytes)
                                put("width", image.width)
                                put("height", image.height)
                            },
                            visualArtifact = image,
                        )
                    }
                } catch (e: VisualPreparationException) {
                    ToolExecutorResult.Failed("Image unavailable: ${e.code}", sideEffectFree = true)
                } catch (_: IllegalArgumentException) {
                    ToolExecutorResult.Failed("Image requires a valid authorized workspace path", sideEffectFree = true)
                }
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        preparation: ToolVisualPreparation,
    ) {
        val descriptor = descriptor()
        registry.register(descriptor)
        implementations.register(descriptor, executor(preparation))
    }
}

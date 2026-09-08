package com.helix.app.skills

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

internal object SkillAuthoringTools {
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        service: SkillAuthoringService,
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("skills.preview"),
                version = ToolVersion(1),
                description = "Validate a Workspace Skill directory or ZIP and return its hash and file manifest.",
                inputSchema = Json.parseToJsonElement(INPUT).jsonObject,
                outputSchema = Json.parseToJsonElement(OUTPUT).jsonObject,
                operationClass = ToolOperationClass.READ_ONLY,
                baseRisk = RiskLevel.L1,
                timeout = 30.seconds,
                maxOutputBytes = 256L * 1024,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )
        registry.register(descriptor)
        implementations.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                    try {
                        val path =
                            call.args
                                .getValue("path")
                                .jsonPrimitive.content
                        val preview = service.preview(path) { call.cancel.isCancelled() }
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("name", JsonPrimitive(preview.name))
                                put("description", JsonPrimitive(preview.description))
                                put("compatibility", JsonPrimitive(preview.compatibility.orEmpty()))
                                put("declaredAllowedTools", JsonPrimitive(preview.declaredAllowedTools.orEmpty()))
                                put("hash", JsonPrimitive(preview.snapshotHash))
                                put("path", JsonPrimitive(path))
                                put(
                                    "files",
                                    JsonArray(
                                        preview.files.map { file ->
                                            buildJsonObject {
                                                put("path", JsonPrimitive(file.relativePath))
                                                put("sha256", JsonPrimitive(file.sha256))
                                                put("sizeBytes", JsonPrimitive(file.sizeBytes))
                                                put("kind", JsonPrimitive(file.kind.name))
                                            }
                                        },
                                    ),
                                )
                            },
                        )
                    } catch (failure: com.helix.extensions.skills.InvalidSkillException) {
                        ToolExecutorResult.Failed("SKILL_PREVIEW_FAILED: ${failure.message.orEmpty().take(512)}")
                    } catch (_: Exception) {
                        if (call.cancel.isCancelled()) {
                            ToolExecutorResult.Cancelled
                        } else {
                            ToolExecutorResult.Failed(
                                "SKILL_PREVIEW_FAILED: check source path, archive layout and byte limits",
                            )
                        }
                    }
            },
        )
    }

    private const val INPUT = """{
      "type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":512}},
      "required":["path"],"additionalProperties":false
    }"""
    private const val OUTPUT = """{
      "type":"object","properties":{
        "name":{"type":"string"},"hash":{"type":"string"},"path":{"type":"string"},
        "description":{"type":"string"},"compatibility":{"type":"string"},"declaredAllowedTools":{"type":"string"},
        "files":{"type":"array","items":{"type":"object","properties":{
          "path":{"type":"string"},"sha256":{"type":"string"},"sizeBytes":{"type":"integer"},
          "kind":{"type":"string"}},"required":["path","sha256","sizeBytes","kind"]}}
      },"required":["name","hash","path","files"],"additionalProperties":false
    }"""
}

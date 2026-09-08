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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

internal object SkillInstallationTools {
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        service: SkillInstallationService,
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("skills.install"),
                version = ToolVersion(1),
                description = "Install a reviewed Workspace Skill using its preview hash. New snapshots stay disabled.",
                inputSchema = Json.parseToJsonElement(INPUT).jsonObject,
                outputSchema = Json.parseToJsonElement(OUTPUT).jsonObject,
                operationClass = ToolOperationClass.LOCAL_MUTATION,
                baseRisk = RiskLevel.L2,
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
                        val key =
                            service.install(
                                path,
                                call.args
                                    .getValue("expectedHash")
                                    .jsonPrimitive.content,
                            ) {
                                call.cancel.isCancelled()
                            }
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("source", JsonPrimitive(key.source.name))
                                put("name", JsonPrimitive(key.name))
                                put("snapshotHash", JsonPrimitive(key.snapshotHash))
                                put("installed", JsonPrimitive(true))
                                put("enabled", JsonPrimitive(service.isEnabled(key)))
                            },
                        )
                    } catch (_: Exception) {
                        ToolExecutorResult.Failed(
                            "SKILL_INSTALL_NOT_CONFIRMED: query skills.list by hash; refresh preview before retry",
                        )
                    }
            },
        )
    }

    private const val INPUT = """{
      "type":"object","properties":{
        "path":{"type":"string","minLength":1,"maxLength":512},
        "expectedHash":{"type":"string","pattern":"^[a-f0-9]{64}$"}},
      "required":["path","expectedHash"],"additionalProperties":false
    }"""
    private const val OUTPUT = """{
      "type":"object","properties":{
        "source":{"type":"string"},"name":{"type":"string"},"snapshotHash":{"type":"string"},
        "installed":{"type":"boolean"},"enabled":{"type":"boolean"}
      },"required":["source","name","snapshotHash","installed","enabled"],"additionalProperties":false
    }"""
}

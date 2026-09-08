package com.helix.app.connector

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

internal object ConnectorInstallationTools {
    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        service: ConnectorInstallationService,
    ) {
        for (install in listOf(false, true)) {
            val descriptor =
                ToolDescriptor(
                    name = ToolName(if (install) "connectors.install" else "connectors.preview"),
                    version = ToolVersion(1),
                    description =
                        if (install) {
                            "Install a reviewed local Connector JSON/ZIP with its exact hash; no connection."
                        } else {
                            "Preview local MCP JSON or ZIP: hash, endpoints, Skills and diagnostics."
                        },
                    inputSchema = Json.parseToJsonElement(if (install) INPUT else PREVIEW_INPUT).jsonObject,
                    outputSchema = Json.parseToJsonElement(if (install) OUTPUT else PREVIEW_OUTPUT).jsonObject,
                    operationClass = if (install) ToolOperationClass.LOCAL_MUTATION else ToolOperationClass.READ_ONLY,
                    baseRisk = if (install) RiskLevel.L2 else RiskLevel.L1,
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
                            val output =
                                if (install) {
                                    val record =
                                        service.install(
                                            path,
                                            call.args
                                                .getValue("expectedHash")
                                                .jsonPrimitive.content,
                                        ) {
                                            call.cancel.isCancelled()
                                        }
                                    buildJsonObject {
                                        put("id", JsonPrimitive(record.id))
                                        put("name", JsonPrimitive(record.name))
                                        put("hash", JsonPrimitive(record.hash))
                                        put("installed", JsonPrimitive(true))
                                    }
                                } else {
                                    val bundle = service.preview(path) { call.cancel.isCancelled() }
                                    previewOutput(bundle, path, service.installedId(bundle.contentHash))
                                }
                            ToolExecutorResult.Completed(output)
                        } catch (_: Exception) {
                            ToolExecutorResult.Failed(
                                "CONNECTOR_NOT_CONFIRMED: check installed hash; refresh preview",
                            )
                        }
                },
            )
        }
    }

    private fun previewOutput(
        bundle: com.helix.extensions.skills.connector.ConnectorPackage,
        path: String,
        installedId: String?,
    ) = buildJsonObject {
        put("name", JsonPrimitive(bundle.name))
        put("hash", JsonPrimitive(bundle.contentHash))
        put("source", JsonPrimitive(bundle.source))
        put("installedId", JsonPrimitive(installedId.orEmpty()))
        put("path", JsonPrimitive(path))
        put(
            "endpoints",
            kotlinx.serialization.json.JsonArray(
                bundle.endpoints.map { endpoint ->
                    buildJsonObject {
                        put("name", JsonPrimitive(endpoint.name))
                        put("url", JsonPrimitive(endpoint.url))
                        put("needsCredential", JsonPrimitive(endpoint.needsCredential))
                    }
                },
            ),
        )
        put(
            "skills",
            kotlinx.serialization.json.JsonArray(
                bundle.skills.map { skill ->
                    buildJsonObject {
                        put("name", JsonPrimitive(skill.directory))
                        put(
                            "files",
                            kotlinx.serialization.json.JsonArray(
                                skill.files.toSortedMap().map { (name, bytes) ->
                                    buildJsonObject {
                                        put("path", JsonPrimitive(name))
                                        put("sizeBytes", JsonPrimitive(bytes.size))
                                    }
                                },
                            ),
                        )
                    }
                },
            ),
        )
        put("diagnostics", kotlinx.serialization.json.JsonArray(bundle.diagnostics.map { JsonPrimitive(it) }))
    }

    private const val INPUT = """{
      "type":"object","properties":{
        "path":{"type":"string","minLength":1,"maxLength":512},
        "expectedHash":{"type":"string","pattern":"^[a-f0-9]{64}$"}},
      "required":["path","expectedHash"],"additionalProperties":false
    }"""
    private const val OUTPUT = """{
      "type":"object","properties":{
        "id":{"type":"string"},"name":{"type":"string"},"hash":{"type":"string"},"installed":{"type":"boolean"}
      },"required":["id","name","hash","installed"],"additionalProperties":false
    }"""
    private const val PREVIEW_INPUT = """{
      "type":"object","properties":{"path":{"type":"string","minLength":1,"maxLength":512}},
      "required":["path"],"additionalProperties":false
    }"""
    private const val PREVIEW_OUTPUT = """{
      "type":"object","properties":{
        "name":{"type":"string"},"hash":{"type":"string"},"source":{"type":"string"},"path":{"type":"string"},
        "installedId":{"type":"string"},
        "endpoints":{"type":"array","items":{"type":"object","properties":{
          "name":{"type":"string"},"url":{"type":"string"},"needsCredential":{"type":"boolean"}},
          "required":["name","url","needsCredential"],"additionalProperties":false}},
        "skills":{"type":"array","items":{"type":"object","properties":{
          "name":{"type":"string"},"files":{"type":"array","items":{"type":"object","properties":{
            "path":{"type":"string"},"sizeBytes":{"type":"integer"}},"required":["path","sizeBytes"]}}},
          "required":["name","files"]}},
        "diagnostics":{"type":"array","items":{"type":"string"}}
      },"required":["name","hash","source","path","endpoints","skills","diagnostics"],"additionalProperties":false
    }"""
}

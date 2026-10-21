package com.helix.app.settings

import com.helix.app.AppContainer
import com.helix.core.model.Capability
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

internal object HelixSettingsTool {
    fun descriptor() =
        ToolDescriptor(
            name = ToolName("settings.inspect"),
            version = ToolVersion(1),
            description =
                "Inspect this session's saved mode/model/reasoning, configured selectable models " +
                    "and device capability availability. Takes no arguments and changes nothing. " +
                    "Use settings.apply for authorized future-input changes, or device.open_page for navigation. " +
                    "Capability availability is not tool authorization. " +
                    "Missing permission requires user action.",
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{},"additionalProperties":false}""",
                    ).jsonObject,
            outputSchema = SettingsToolSchemas.inspectOutput,
            operationClass = ToolOperationClass.READ_ONLY,
            timeout = 5.seconds,
            maxOutputBytes = 32768,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun register(
        registry: ToolRegistry,
        container: () -> AppContainer,
    ) {
        registry.register(descriptor(), executor(container))
        HelixSettingsApplyTool.register(registry, container)
    }

    fun executor(container: () -> AppContainer) =
        object : ToolExecutor {
            @Suppress("ReturnCount") // Reject stale calls and obsolete args before container access.
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                if (!java.time.Instant
                        .now()
                        .isBefore(call.deadline)
                ) {
                    return ToolExecutorResult.TimedOut
                }
                if (call.args.isNotEmpty()) {
                    return ToolExecutorResult.Failed(
                        "settings.inspect takes no arguments; use settings.apply or device.open_page.",
                        sideEffectFree = true,
                    )
                }
                return ToolExecutorResult.Completed(inspect(call, container()))
            }
        }

    private fun inspect(
        call: ExecutableToolCall,
        app: AppContainer,
    ): JsonObject =
        buildJsonObject {
            call.sessionId?.let { sessionId ->
                app.storage.sessions.find(sessionId)?.let { session ->
                    put("currentProviderId", session.providerId)
                    put("currentModel", session.modelId)
                }
                app.storage.sessionRunControls.forSession(sessionId)?.let { control ->
                    put("currentMode", control.mode.name)
                    put("currentReasoning", control.reasoning.name)
                }
            }
            put("profile", app.profileStore.profile.name)
            put("advancedAvailable", app.manualTerminal != null)
            put(
                "guidance",
                "Standard supports files, JavaScript, browser and authorized Android tools. " +
                    "Advanced adds Linux/terminal on developer builds, without granting permissions. " +
                    "Use tools.search for optional tools. Missing tools may not need Advanced. " +
                    "DENIED/LOST requires user permission action; UNAVAILABLE means platform/build unavailable. " +
                    "GRANTED is platform availability, not per-call scope authorization.",
            )
            put(
                "capabilities",
                buildJsonObject {
                    Capability.entries.forEach {
                        put(
                            it.name,
                            app.capabilityCenter
                                .resolveOnly(it)
                                .state.name,
                        )
                    }
                },
            )
            put(
                "models",
                buildJsonArray {
                    app.providerService.rows.value.filter { it.chatSelectable }.take(40).forEach { row ->
                        add(
                            buildJsonObject {
                                put("providerId", row.id)
                                put("displayName", row.displayName)
                                put("model", row.model)
                                put(
                                    "reasoning",
                                    buildJsonArray {
                                        val efforts = app.providerService.reasoningOptions(row.id, row.model)
                                        efforts.forEach { add(it.name) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
}

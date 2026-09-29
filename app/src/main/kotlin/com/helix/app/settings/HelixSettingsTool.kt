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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/** A proposal is ephemeral UI input, never an applied configuration or a permission grant. */
class HelixSettingsRequests {
    data class Proposal(
        val id: String,
        val sessionId: String,
        val values: JsonObject,
    )

    private val state = MutableStateFlow<List<Proposal>>(emptyList())
    val pending = state.asStateFlow()

    @Synchronized
    fun offer(proposal: Proposal) {
        state.value = state.value.filterNot { it.sessionId == proposal.sessionId } + proposal
    }

    @Synchronized
    fun remove(id: String) {
        state.value = state.value.filterNot { it.id == id }
    }
}

internal object HelixSettingsTool {
    fun register(
        registry: ToolRegistry,
        container: () -> AppContainer,
    ) {
        val descriptor =
            ToolDescriptor(
                name = ToolName("helix.settings"),
                version = ToolVersion(1),
                description =
                    "Inspect capabilities/models or propose settings for THIS session. " +
                        "Use helix.settings.apply for authorized configuration changes. " +
                        "propose is a legacy optional UI shortcut; it does NOT apply changes. " +
                        "Use page for a settings shortcut. Never claim changes already applied. " +
                        "Missing permissions require user action. Advanced never bypasses permissions.",
                inputSchema =
                    Json
                        .parseToJsonElement(
                            """
                {"type":"object","properties":{
                "action":{"type":"string","enum":["inspect","propose"]},
                "page":{"type":"string","enum":["settings","models","permissions","session"]},
                "mode":{"type":"string","enum":["CHAT","PLAN","ACT","GOAL"]},
                "providerId":{"type":"string","maxLength":256},
                "model":{"type":"string","maxLength":256},
                "reasoning":{"type":"string","maxLength":32,"pattern":"^[A-Za-z][A-Za-z0-9_-]{0,31}$"}
                },"required":["action"],"additionalProperties":false}
                """,
                        ).jsonObject,
                outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
                operationClass = ToolOperationClass.METADATA,
                timeout = 5.seconds,
                maxOutputBytes = 32768,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )

        registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = executeSettings(call, container())
            },
        )
        HelixSettingsApplyTool.register(registry, container)
    }

    @Suppress("ReturnCount") // Cancellation, expiration, proposal and inspection are distinct outcomes.
    private fun executeSettings(call: ExecutableToolCall, app: AppContainer): ToolExecutorResult {
        if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
        if (java.time.Instant
                .now()
                .isAfter(call.deadline)
        ) {
            return ToolExecutorResult.TimedOut
        }
        if (call.args["action"]?.jsonPrimitive?.content == "propose") {
            if (call.args.keys.any { it in setOf("mode", "providerId", "model", "reasoning") }) {
                return ToolExecutorResult.Failed(
                    "Use helix.settings.apply. Configuration changes require normal tool authorization.",
                    sideEffectFree = true,
                )
            }
            val session = requireNotNull(call.sessionId)
            requireNotNull(app.settingsRequests).offer(
                HelixSettingsRequests.Proposal(
                    "$session:${call.turnId.orEmpty()}:${call.toolCallId}",
                    session,
                    call.args,
                ),
            )
            return ToolExecutorResult.Completed(
                buildJsonObject {
                    put("status", "AWAITING_USER_CONFIRMATION")
                    put("applied", false)
                    put("note", "Shown when idle. Discarded after process death.")
                },
            )
        }
        return ToolExecutorResult.Completed(inspect(call, app))
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

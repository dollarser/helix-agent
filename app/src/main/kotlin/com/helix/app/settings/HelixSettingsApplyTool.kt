package com.helix.app.settings

import com.helix.app.AppContainer
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/** Configuration is a mutation, not a metadata permission exemption. */
internal object HelixSettingsApplyTool {
    fun descriptor() =
        ToolDescriptor(
            name = ToolName("helix.settings.apply"),
            version = ToolVersion(1),
            description =
                "Change THIS session's mode/model/reasoning defaults for future inputs through normal tool " +
                    "authorization. The running turn retains its original configuration. " +
                    "Pending inputs must drain first. " +
                    "Use helix.settings inspect to find configured models. Cannot grant permissions, enable native " +
                    "QuickJS, change profile, or create credentials. No separate settings confirmation is needed.",
            inputSchema =
                Json
                    .parseToJsonElement(
                        """{"type":"object","properties":{
            "mode":{"type":"string","enum":["CHAT","PLAN","ACT","GOAL"]},
            "providerId":{"type":"string","maxLength":256},
            "model":{"type":"string","maxLength":256},
            "reasoning":{"type":"string","maxLength":32,"pattern":"^[A-Za-z][A-Za-z0-9_-]{0,31}$"}
            },"additionalProperties":false}""",
                    ).jsonObject,
            outputSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            timeout = 5.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun register(
        registry: ToolRegistry,
        container: () -> AppContainer,
    ) {
        val descriptor = descriptor()

        registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                    val applied =
                        call.args.isNotEmpty() && runBlocking { container().chatService.applyToolSettings(call) }
                    return if (applied) {
                        ToolExecutorResult.Completed(
                            buildJsonObject {
                                put("applied", true)
                                put("effectiveFor", "future_inputs")
                            },
                        )
                    } else {
                        ToolExecutorResult.Failed(
                            "SETTINGS_NOT_APPLIED: re-inspect models/session; pending inputs must drain first.",
                            sideEffectFree = true,
                        )
                    }
                }
            },
        )
    }
}

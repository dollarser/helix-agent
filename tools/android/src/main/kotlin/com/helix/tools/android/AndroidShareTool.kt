@file:Suppress("TooManyFunctions") // android.*/clipboard.* tools share the internal schema/arg helpers

package com.helix.tools.android

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object AndroidShareTool {
    const val NAME: String = "android.share"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Share [text] (optional [subject]) through the device's system share chooser. The text " +
                    "is previewed in the approval card before the user approves. Helix never picks a " +
                    "target app itself — the user always chooses.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("text", stringSchema(MAX_SHARE_TEXT, "The text to share."))
                            put("subject", stringSchema(MAX_SHARE_SUBJECT, "Optional share subject."))
                        },
                    required = listOf("text"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_SHARED, ST_NO_HANDLER),
                                    "shared, or no-handler (no app to share to).",
                                ),
                            )
                            put("reason", stringSchema(128, "Stable note or error; empty on a launched share."))
                        },
                    required = listOf("status", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val text =
                    strArg(call.args, "text", MAX_SHARE_TEXT)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'android.share' arguments: 'text' must be a non-empty string",
                        )
                val subject = (call.args["subject"] as? JsonPrimitive)?.content.orEmpty()
                val out = bridge.share(text, subject)
                val status =
                    when (out.status) {
                        ShareStatus.SHARED -> ST_SHARED
                        ShareStatus.NO_HANDLER -> ST_NO_HANDLER
                        ShareStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d)
        implementations.register(d, executor(bridge))
    }
}

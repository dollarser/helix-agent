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

object ClipboardReadTool {
    const val NAME: String = "clipboard.read"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Read the current text of the device's system clipboard. Refused unless Helix is the " +
                    "visible-foreground app. An empty clipboard is a successful read of empty text.",
            inputSchema =
                buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("additionalProperties", JsonPrimitive(false))
                },
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_READ, ANDROID_SYSTEM_REFUSED),
                                    "read, or refused (not the visible-foreground app).",
                                ),
                            )
                            put(
                                "text",
                                stringSchema(
                                    MAX_CLIPBOARD_READ,
                                    "The clipboard text, bounded; empty for an empty clipboard or a refusal.",
                                ),
                            )
                            put("length", integerSchema("The original clipboard char count (before bounding)."))
                            put("truncated", androidSystemToolsBooleanSchema("True when text was cut to the bound."))
                            put(
                                "reason",
                                stringSchema(128, "not-foreground on a refusal or an error note; empty on a read."),
                            )
                        },
                    required = listOf("status", "text", "length", "truncated", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 16_384,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            @Suppress("ReturnCount")
            override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                if (call.cancel.isCancelled()) return ToolExecutorResult.Cancelled
                val out = bridge.clipboardRead()
                val status =
                    when (out.status) {
                        ClipboardReadStatus.READ -> ST_READ
                        ClipboardReadStatus.REFUSED -> ANDROID_SYSTEM_REFUSED
                        ClipboardReadStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("text", JsonPrimitive(out.text))
                        put("length", JsonPrimitive(out.length))
                        put("truncated", JsonPrimitive(out.truncated))
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

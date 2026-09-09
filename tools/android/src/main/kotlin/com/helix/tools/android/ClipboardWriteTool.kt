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

object ClipboardWriteTool {
    const val NAME: String = "clipboard.write"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Write [text] to the device's system clipboard. Refused unless Helix is the " +
                    "visible-foreground app.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("text", stringSchema(MAX_CLIPBOARD_WRITE, "The text to place on the clipboard."))
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
                                    listOf(ST_WRITTEN, ANDROID_SYSTEM_REFUSED),
                                    "written, or refused (not the visible-foreground app).",
                                ),
                            )
                            put("length", integerSchema("The char count written (0 when refused)."))
                            put(
                                "reason",
                                stringSchema(128, "not-foreground on a refusal or an error note; empty on a write."),
                            )
                        },
                    required = listOf("status", "length", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
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
                val text =
                    strArg(call.args, "text", MAX_CLIPBOARD_WRITE)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'clipboard.write' arguments: 'text' must be a non-empty string",
                        )
                val out = bridge.clipboardWrite(text)
                val status =
                    when (out.status) {
                        ClipboardWriteStatus.WRITTEN -> ST_WRITTEN
                        ClipboardWriteStatus.REFUSED -> ANDROID_SYSTEM_REFUSED
                        ClipboardWriteStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("length", JsonPrimitive(out.length))
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

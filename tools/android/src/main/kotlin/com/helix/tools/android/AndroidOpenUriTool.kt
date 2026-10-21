@file:Suppress("TooManyFunctions") // android.*/clipboard.* tools share the internal schema/arg helpers

package com.helix.tools.android

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

object AndroidOpenUriTool {
    const val NAME: String = "device.open_url"

    const val VERSION: Int = 1

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description =
                "Open a known/verified http/https URL via Android's system handler; Android chooses the app. " +
                    "Installation alone does not ensure app routing: URL paths, domain approval and user defaults " +
                    "matter. A homepage may open in a browser. opened only means launch accepted; observe the actual " +
                    "app/page before claiming success. To open an installed app itself, use available authorized " +
                    "ui.apps then ui.launch. Only opens the link; no navigation or follow-on app control. " +
                    "Prefer known links over browser typing. Never invent download URLs; prefer an installed app " +
                    "store, otherwise verify the official download URL with fetch/browser first. If unknown-source " +
                    "permission blocks installation, retain the verified APK and use device.open_settings for the " +
                    "source package rather than switching stores. Non-http(s) URLs refused.",
            inputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put("url", stringSchema(MAX_URL, "The http/https URL to open."))
                        },
                    required = listOf("url"),
                ),
            outputSchema =
                objectSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "status",
                                enumSchema(
                                    listOf(ST_OPENED, ANDROID_SYSTEM_REFUSED, ST_NO_HANDLER),
                                    "opened (launch accepted; app/page unverified), refused (scheme) or no-handler.",
                                ),
                            )
                            put("url", stringSchema(MAX_URL, "The URL that was opened (or requested)."))
                            put("reason", stringSchema(128, "Refusal category or stable note; empty on success."))
                        },
                    required = listOf("status", "url", "reason"),
                ),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
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
                val url =
                    strArg(call.args, "url", MAX_URL)
                        ?: return ToolExecutorResult.Failed(
                            "invalid 'device.open_url' arguments: 'url' must be a non-empty string",
                        )
                val out = bridge.openUri(url)
                val status =
                    when (out.status) {
                        OpenUriStatus.OPENED -> ST_OPENED
                        OpenUriStatus.REFUSED -> ANDROID_SYSTEM_REFUSED
                        OpenUriStatus.NO_HANDLER -> ST_NO_HANDLER
                        OpenUriStatus.ERROR -> return ToolExecutorResult.Failed(bounded(out.reason))
                    }
                return ToolExecutorResult.Completed(
                    buildJsonObject {
                        put("status", JsonPrimitive(status))
                        put("url", JsonPrimitive(out.url))
                        put("reason", JsonPrimitive(out.reason))
                    },
                )
            }
        }

    fun register(
        registry: ToolRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()

        registry.register(d, executor(bridge))
    }
}

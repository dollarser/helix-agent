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

object AndroidOpenSettingsTool {
    const val NAME: String = "android.open_settings"
    const val VERSION: Int = 1

    private val targets = AndroidSettingsTarget.entries.map { it.wire }

    fun descriptor(): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(NAME),
            version = ToolVersion(VERSION),
            description = description(),
            inputSchema = inputSchema(),
            outputSchema = outputSchema(),
            operationClass = ToolOperationClass.EXTERNAL_ACTION,
            timeout = 30.seconds,
            maxOutputBytes = 4096,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun description(): String =
        "Open a specific Android system Settings page for a package. " +
            "Use action='unknown_app_sources' when an APK install is blocked because the current source " +
            "(for example a browser) is not allowed to install unknown apps; opening Settings does not " +
            "grant the permission, so continue with Mobile Use to change the visible toggle when the " +
            "user's task and current session scope permit it. Treat Settings as a temporary detour: after " +
            "the setting changes, go back once and observe; if the interrupted surface is not restored, " +
            "retry the exact original action once. For APK installation, reopen the same verified APK URI. " +
            "Do not switch download sources merely because this permission is off. " +
            "action='app_details' opens the package's normal app-info page."

    private fun inputSchema() =
        objectSchema(
            properties =
                buildJsonObject {
                    put(
                        "action",
                        enumSchema(
                            targets,
                            "Settings page: unknown_app_sources or app_details.",
                        ),
                    )
                    put(
                        "packageName",
                        stringSchema(
                            MAX_PACKAGE_NAME,
                            "Exact Android package whose Settings page should be opened.",
                        ),
                    )
                },
            required = listOf("action", "packageName"),
        )

    private fun outputSchema() =
        objectSchema(
            properties =
                buildJsonObject {
                    put(
                        "status",
                        enumSchema(
                            listOf(ST_OPENED, ST_NO_HANDLER),
                            "opened, or no-handler when this Android build has no matching Settings page.",
                        ),
                    )
                    put("action", stringSchema(64, "The requested settings action."))
                    put("packageName", stringSchema(MAX_PACKAGE_NAME, "The requested package name."))
                    put("reason", stringSchema(128, "Stable note; empty on success."))
                    put(
                        "continuation",
                        enumSchema(
                            listOf(CONTINUATION_RETURN_THEN_RETRY),
                            "After changing the setting, return to the interrupted task and " +
                                "retry its exact action once if needed.",
                        ),
                    )
                },
            required = listOf("status", "action", "packageName", "reason", "continuation"),
        )

    fun executor(bridge: AndroidSystemBridge): ToolExecutor =
        object : ToolExecutor {
            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                if (call.cancel.isCancelled()) {
                    ToolExecutorResult.Cancelled
                } else {
                    executeActive(call, bridge)
                }
        }

    private fun executeActive(
        call: ExecutableToolCall,
        bridge: AndroidSystemBridge,
    ): ToolExecutorResult {
        val request =
            parseRequest(call.args)
                ?: return ToolExecutorResult.Failed(
                    "invalid 'android.open_settings' arguments: expected a supported action and valid packageName",
                )
        return mapOutcome(bridge.openSettings(request.action, request.packageName))
    }

    private fun parseRequest(args: kotlinx.serialization.json.JsonObject): SettingsRequest? {
        val action = strArg(args, "action", 64)?.let(AndroidSettingsTarget::fromWire)
        val packageName =
            strArg(args, "packageName", MAX_PACKAGE_NAME)
                ?.takeIf(::isAndroidPackageName)
        return if (action != null && packageName != null) SettingsRequest(action, packageName) else null
    }

    private fun mapOutcome(out: OpenSettingsOutcome): ToolExecutorResult =
        when (out.status) {
            OpenSettingsStatus.ERROR -> {
                ToolExecutorResult.Failed(bounded(out.reason))
            }

            OpenSettingsStatus.OPENED, OpenSettingsStatus.NO_HANDLER -> {
                ToolExecutorResult.Completed(
                    buildJsonObject {
                        put(
                            "status",
                            JsonPrimitive(
                                if (out.status == OpenSettingsStatus.OPENED) ST_OPENED else ST_NO_HANDLER,
                            ),
                        )
                        put("action", JsonPrimitive(out.action.wire))
                        put("packageName", JsonPrimitive(out.packageName))
                        put("reason", JsonPrimitive(out.reason))
                        put("continuation", JsonPrimitive(CONTINUATION_RETURN_THEN_RETRY))
                    },
                )
            }
        }

    private data class SettingsRequest(
        val action: AndroidSettingsTarget,
        val packageName: String,
    )

    fun register(
        registry: ToolRegistry,
        bridge: AndroidSystemBridge,
    ) {
        val d = descriptor()
        registry.register(d, executor(bridge))
    }

    private const val CONTINUATION_RETURN_THEN_RETRY = "return_then_retry_original_action"
    private const val MAX_PACKAGE_NAME = 255
}

enum class AndroidSettingsTarget(
    val wire: String,
) {
    UNKNOWN_APP_SOURCES("unknown_app_sources"),
    APP_DETAILS("app_details"),
    ;

    companion object {
        fun fromWire(value: String): AndroidSettingsTarget? = entries.singleOrNull { it.wire == value }
    }
}

internal fun isAndroidPackageName(value: String): Boolean =
    value.length in 3..255 &&
        value.contains('.') &&
        value.split('.').all { part ->
            part.isNotEmpty() &&
                (part.first().isLetter() || part.first() == '_') &&
                part.all { it.isLetterOrDigit() || it == '_' }
        }

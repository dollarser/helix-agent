package com.helix.extensions.plugin

/** Package support and live readiness use these reasons; they never grant execution authority. */
enum class PluginComponentState {
    AVAILABLE,
    NEEDS_CONFIGURATION,
    NOT_CONNECTED,
    HOST_UNAVAILABLE,
    NOT_SELECTED,
    DISABLED,
    UNSUPPORTED,
    METADATA_ONLY,
    REQUEST_UNAVAILABLE,
    RELOAD_REQUIRED,
}

enum class PluginComponentKind { SKILL, MCP, NATIVE, BROWSER, DEPENDENCY, CONFIGURATION, OTHER }

enum class PluginRepairAction { NONE, CONFIGURE, CONNECT, OPEN_HOST_SETTINGS, REVIEW_PACKAGE, RELOAD }

data class PluginDiagnostic(
    val code: String,
    val kind: PluginComponentKind,
    val path: String,
    val state: PluginComponentState,
    val action: PluginRepairAction,
    val omitted: Boolean,
)

/** One normalization point for the parser's bounded audit codes, shared by every consumer. */
object PluginDiagnostics {
    private val omittedCodes =
        setOf(
            "PLUGIN_MCP_CONFIG_INVALID",
            "PLUGIN_MCP_NOT_FILE",
            "PLUGIN_MCP_SERVER_INVALID",
            "PLUGIN_SKILL_INVALID",
            "PLUGIN_SKILLS_NOT_DIRECTORY",
            "HOST_NATIVE_COMPONENT_NOT_IMPORTED",
            "STDIO_REQUIRES_ANDROID_RUNTIME",
            "UNSUPPORTED_TRANSPORT",
            "ENDPOINT_REQUIRES_CONFIGURATION",
            "PLUGIN_NO_SUPPORTED_COMPONENTS",
        )

    fun project(auditCode: String): PluginDiagnostic {
        val code = auditCode.substringBefore(':')
        val member = auditCode.substringAfter(':', "")
        val kind =
            when {
                code.contains("SKILL") -> {
                    PluginComponentKind.SKILL
                }

                code.contains("MCP") || code in
                    setOf(
                        "STDIO_REQUIRES_ANDROID_RUNTIME",
                        "UNSUPPORTED_TRANSPORT",
                        "ENDPOINT_REQUIRES_CONFIGURATION",
                        "AUTH_REQUIRES_CONFIGURATION",
                        "UNSUPPORTED_SERVER_OPTIONS",
                    )
                -> {
                    PluginComponentKind.MCP
                }

                code == "HOST_NATIVE_COMPONENT_NOT_IMPORTED" -> {
                    PluginComponentKind.NATIVE
                }

                else -> {
                    PluginComponentKind.OTHER
                }
            }
        val omitted = code in omittedCodes
        val state = stateFor(code, omitted)
        val path =
            when (kind) {
                PluginComponentKind.SKILL -> "skills/${member.substringBefore(':')}"
                PluginComponentKind.MCP -> "mcpServers/$member"
                PluginComponentKind.NATIVE -> "extensions/com.helix.agent/runtime"
                else -> member.ifEmpty { "plugin.json" }
            }
        return PluginDiagnostic(
            code,
            kind,
            path,
            state,
            if (state == PluginComponentState.NEEDS_CONFIGURATION) {
                PluginRepairAction.CONFIGURE
            } else {
                PluginRepairAction.REVIEW_PACKAGE
            },
            omitted,
        )
    }

    private fun stateFor(
        code: String,
        omitted: Boolean,
    ): PluginComponentState =
        when {
            code in setOf("ENDPOINT_REQUIRES_CONFIGURATION", "AUTH_REQUIRES_CONFIGURATION") -> {
                PluginComponentState.NEEDS_CONFIGURATION
            }

            omitted || code.startsWith("UNSUPPORTED_") -> {
                PluginComponentState.UNSUPPORTED
            }

            else -> {
                PluginComponentState.METADATA_ONLY
            }
        }
}

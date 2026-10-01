package com.helix.app.plugin

/** Component usability is independent of installation, session selection and execution permission. */
internal class PluginComponentReadiness(
    enabled: Boolean,
    components: List<Boolean>,
    diagnostics: List<String> = emptyList(),
) {
    val total: Int = components.size
    val ready: Int = if (enabled) components.count { it } else 0
    val hasSkippedComponents: Boolean =
        diagnostics.any { diagnostic ->
            SKIPPED_PREFIXES.any(diagnostic::startsWith)
        }
    val fullyReady: Boolean = total > 0 && ready == total && !hasSkippedComponents

    private companion object {
        val SKIPPED_PREFIXES =
            listOf(
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
    }
}

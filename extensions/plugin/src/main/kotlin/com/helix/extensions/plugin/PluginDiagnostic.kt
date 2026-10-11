package com.helix.extensions.plugin

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Package support and live readiness never grant execution authority. */
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

enum class PluginComponentKind {
    SKILL,
    MCP,
    NATIVE,
    BROWSER,
    DEPENDENCY,
    CONFIGURATION,
    HOOK,
    AGENT,
    COMMAND,
    LSP,
    APP,
    OTHER,
}

enum class PluginRepairAction { NONE, CONFIGURE, CONNECT, OPEN_HOST_SETTINGS, REVIEW_PACKAGE, RELOAD }

enum class PluginDependencyRequirement { NONE, REQUIRED, OPTIONAL, RUNTIME }

data class PluginDiagnostic(
    val code: String,
    val kind: PluginComponentKind,
    val path: String,
    val state: PluginComponentState,
    val action: PluginRepairAction,
    val omitted: Boolean,
    val requirement: PluginDependencyRequirement = PluginDependencyRequirement.NONE,
) {
    fun modelFacts() =
        buildJsonObject {
            put("domain", "PACKAGE_SUPPORT")
            put("code", code)
            put("kind", kind.name)
            put("path", path)
            put("state", state.name)
            put("action", action.name)
            put("omitted", omitted)
            put("requirement", requirement.name)
        }
}

/** Single projection of production parser facts; consumers do not interpret audit strings. */
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
            "UNSUPPORTED_MCP_SCHEMA",
            "UNSUPPORTED_SKILL_REFERENCE",
            "UNSUPPORTED_MCP_CONFIG_REFERENCE",
        )
    private val configurationCodes = setOf("ENDPOINT_REQUIRES_CONFIGURATION", "AUTH_REQUIRES_CONFIGURATION")
    private val unsupportedKinds =
        mapOf(
            "UNSUPPORTED_HOOKS" to PluginComponentKind.HOOK,
            "UNSUPPORTED_AGENTS" to PluginComponentKind.AGENT,
            "UNSUPPORTED_COMMANDS" to PluginComponentKind.COMMAND,
            "UNSUPPORTED_LSPSERVERS" to PluginComponentKind.LSP,
            "HOST_APP_REQUIRES_NEW_CONNECTION" to PluginComponentKind.APP,
            "CONFIG_SCHEMA_NOT_APPLIED" to PluginComponentKind.CONFIGURATION,
            "UNSUPPORTED_DEPENDENCIES" to PluginComponentKind.DEPENDENCY,
            "UNSUPPORTED_REQUIRED_DEPENDENCY" to PluginComponentKind.DEPENDENCY,
            "UNSUPPORTED_OPTIONAL_DEPENDENCY" to PluginComponentKind.DEPENDENCY,
            "SKILL_REQUIRES_BINARY" to PluginComponentKind.DEPENDENCY,
            "DESKTOP_SETUP_NOT_EXECUTED" to PluginComponentKind.DEPENDENCY,
        )

    fun project(auditCode: String): PluginDiagnostic {
        val code = auditCode.substringBefore(':')
        val member = auditCode.substringAfter(':', "").filter { it.code >= 32 }.take(160)
        val kind = kindFor(code)
        val omitted = code in omittedCodes || (code.startsWith("UNSUPPORTED_") && code != "UNSUPPORTED_SERVER_OPTIONS")
        val state =
            when {
                code in configurationCodes -> PluginComponentState.NEEDS_CONFIGURATION
                omitted -> PluginComponentState.UNSUPPORTED
                else -> PluginComponentState.METADATA_ONLY
            }
        return PluginDiagnostic(
            code,
            kind,
            pathFor(code, kind, member),
            state,
            if (code in configurationCodes || kind == PluginComponentKind.CONFIGURATION) {
                PluginRepairAction.CONFIGURE
            } else {
                PluginRepairAction.REVIEW_PACKAGE
            },
            omitted,
            requirementFor(code),
        )
    }

    private fun kindFor(code: String): PluginComponentKind =
        unsupportedKinds[code] ?: when {
            code.contains("SKILL") -> {
                PluginComponentKind.SKILL
            }

            code.contains("MCP") || code in configurationCodes || code in
                setOf("STDIO_REQUIRES_ANDROID_RUNTIME", "UNSUPPORTED_TRANSPORT", "UNSUPPORTED_SERVER_OPTIONS") -> {
                PluginComponentKind.MCP
            }

            code == "HOST_NATIVE_COMPONENT_NOT_IMPORTED" -> {
                PluginComponentKind.NATIVE
            }

            else -> {
                PluginComponentKind.OTHER
            }
        }

    private fun requirementFor(code: String): PluginDependencyRequirement =
        when (code) {
            "UNSUPPORTED_REQUIRED_DEPENDENCY" -> {
                PluginDependencyRequirement.REQUIRED
            }

            "UNSUPPORTED_OPTIONAL_DEPENDENCY" -> {
                PluginDependencyRequirement.OPTIONAL
            }

            "SKILL_REQUIRES_BINARY", "STDIO_REQUIRES_ANDROID_RUNTIME", "DESKTOP_SETUP_NOT_EXECUTED" -> {
                PluginDependencyRequirement.RUNTIME
            }

            else -> {
                PluginDependencyRequirement.NONE
            }
        }

    private fun pathFor(
        code: String,
        kind: PluginComponentKind,
        member: String,
    ): String =
        when (kind) {
            PluginComponentKind.SKILL -> {
                "skills/${member.substringBefore(':')}/SKILL.md" +
                    member
                        .substringAfter(':', "")
                        .takeIf { it.isNotEmpty() }
                        ?.let { "/$it" }
                        .orEmpty()
            }

            PluginComponentKind.MCP -> {
                if (member.isEmpty()) "mcp.json" else "mcpServers/$member"
            }

            PluginComponentKind.NATIVE -> {
                "extensions/com.helix.agent/runtime"
            }

            PluginComponentKind.DEPENDENCY -> {
                when (code) {
                    "SKILL_REQUIRES_BINARY" -> "skills/${member.substringBefore(':')}/SKILL.md/metadata/requires"
                    "DESKTOP_SETUP_NOT_EXECUTED" -> member.ifEmpty { "package.json" }
                    else -> "dependencies/$member"
                }
            }

            PluginComponentKind.CONFIGURATION -> {
                member.ifEmpty { "userConfig" }
            }

            else -> {
                member.ifEmpty {
                    if (code ==
                        "UNSUPPORTED_LSPSERVERS"
                    ) {
                        "lspServers"
                    } else {
                        code.removePrefix("UNSUPPORTED_").lowercase()
                    }
                }
            }
        }

    fun components(
        endpoints: List<PluginEndpoint>,
        skillNames: List<String>,
        auditCodes: List<String>,
        nativeRuntime: String? = null,
    ): List<PluginDiagnostic> =
        buildList {
            endpoints.forEach { endpoint ->
                val state =
                    if (endpoint.needsCredential) {
                        PluginComponentState.NEEDS_CONFIGURATION
                    } else {
                        PluginComponentState.AVAILABLE
                    }
                add(
                    PluginDiagnostic(
                        if (endpoint.needsCredential) "AUTH_REQUIRES_CONFIGURATION" else "COMPONENT_AVAILABLE",
                        PluginComponentKind.MCP,
                        "mcpServers/${endpoint.name}",
                        state,
                        if (endpoint.needsCredential) PluginRepairAction.CONFIGURE else PluginRepairAction.CONNECT,
                        false,
                    ),
                )
            }
            skillNames.forEach { name ->
                val unsupported = auditCodes.any { it.startsWith("SKILL_EXECUTION_METADATA_ONLY:$name:") }
                add(
                    PluginDiagnostic(
                        "SKILL_CONTENT",
                        PluginComponentKind.SKILL,
                        "skills/$name/SKILL.md",
                        if (!unsupported) {
                            PluginComponentState.AVAILABLE
                        } else {
                            PluginComponentState.METADATA_ONLY
                        },
                        PluginRepairAction.NONE,
                        false,
                    ),
                )
            }
            nativeRuntime?.let {
                add(
                    PluginDiagnostic(
                        "APK_NATIVE",
                        PluginComponentKind.NATIVE,
                        "extensions/com.helix.agent/runtime",
                        PluginComponentState.AVAILABLE,
                        PluginRepairAction.NONE,
                        false,
                    ),
                )
            }
            addAll(auditCodes.map(::project))
        }.distinct()
}

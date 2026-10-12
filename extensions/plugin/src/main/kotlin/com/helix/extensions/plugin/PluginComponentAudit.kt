package com.helix.extensions.plugin

import com.helix.extensions.skills.SkillDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Inspects declarations only. No dependency resolution, setup, commands or host authorization. */
internal object PluginComponentAudit {
    fun declarations(
        files: Map<String, ByteArray>,
        manifest: JsonObject,
        diagnostics: MutableList<String>,
    ) {
        listOf(
            "hooks",
            "agents",
            "commands",
            "rules",
            "workflows",
            "mcp",
            "lspServers",
            "monitors",
            "ui",
        ).forEach { field ->
            val defaultFilePresent = field == "lspServers" && ".lsp.json" in files
            val declared = field in manifest || defaultFilePresent
            val directoryPresent = files.keys.any { it == field || it.startsWith("$field/") }
            if (declared || directoryPresent) {
                diagnostics += "UNSUPPORTED_${field.uppercase()}"
            }
        }
        if (hasAppMapping(files, manifest)) {
            diagnostics += "HOST_APP_REQUIRES_NEW_CONNECTION"
        }
        listOf("userConfig", "user_config", "configSchema", "authentication").filter { it in manifest }.forEach {
            diagnostics += "CONFIG_SCHEMA_NOT_APPLIED:$it"
        }
        if ("dependencies" in manifest) {
            diagnostics += "UNSUPPORTED_DEPENDENCIES"
            (manifest["dependencies"] as? JsonArray)?.forEachIndexed { index, value ->
                val optional = (value as? JsonObject)?.get("optional") == JsonPrimitive(true)
                diagnostics +=
                    if (optional) {
                        "UNSUPPORTED_OPTIONAL_DEPENDENCY:$index"
                    } else {
                        "UNSUPPORTED_REQUIRED_DEPENDENCY:$index"
                    }
            }
        }
        if ("package.json" in files) diagnostics += "DESKTOP_SETUP_NOT_EXECUTED:package.json"
        if ("scripts" in manifest) diagnostics += "DESKTOP_SETUP_NOT_EXECUTED:scripts"
    }

    private fun hasAppMapping(
        files: Map<String, ByteArray>,
        manifest: JsonObject,
    ): Boolean {
        val openai = (manifest["extensions"] as? JsonObject)?.get("com.openai") as? JsonObject
        return ".app.json" in files || "apps" in manifest ||
            openai?.keys?.any { it in setOf("apps", "mcpServers") } == true
    }

    fun skill(
        document: SkillDocument,
        diagnostics: MutableList<String>,
    ) {
        document.invocation.unsupportedExecutionFields.sorted().forEach {
            diagnostics += "SKILL_EXECUTION_METADATA_ONLY:${document.catalogEntry.name}:$it"
        }
        if (document.allowedTools != null) diagnostics += "SKILL_TOOL_POLICY_NOT_APPLIED:${document.catalogEntry.name}"
        if ("model" in document.additionalFields) diagnostics += "SKILL_MODEL_NOT_APPLIED:${document.catalogEntry.name}"
    }
}

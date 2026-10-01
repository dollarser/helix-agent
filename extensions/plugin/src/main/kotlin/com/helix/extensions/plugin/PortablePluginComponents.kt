package com.helix.extensions.plugin

import com.helix.extensions.skills.InvalidSkillException
import com.helix.extensions.skills.SkillLoader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URISyntaxException

/** Standard fixed-location discovery; foreign host formats retain their own explicit adapters. */
internal object PortablePluginComponents {
    private const val MCP_SCHEMA = "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json"
    private const val MAX_SERVERS = 32
    private const val MAX_SKILLS = 64
    private val headerToken = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun skills(
        files: Map<String, ByteArray>,
        diagnostics: MutableList<String>,
    ): List<PluginSkill> {
        if ("skills" in files) {
            diagnostics += "PLUGIN_SKILLS_NOT_DIRECTORY"
            return emptyList()
        }
        val roots =
            files.keys
                .filter {
                    it.startsWith("skills/") && it.endsWith("/SKILL.md") && it.count { char -> char == '/' } == 2
                }.map { it.removeSuffix("/SKILL.md") }
                .sorted()
        require(roots.size <= MAX_SKILLS) { "CONNECTOR_TOO_MANY_SKILLS" }
        return roots.mapNotNull { path ->
            val name = path.substringAfterLast('/')
            val content =
                files
                    .filterKeys { it.startsWith("$path/") }
                    .mapKeys { (key, _) -> key.removePrefix("$path/") }
                    .mapValues { it.value.copyOf() }
            try {
                SkillLoader().validatePackageMember(content.getValue("SKILL.md"), name)
                PluginSkill(name, content)
            } catch (_: InvalidSkillException) {
                diagnostics += "PLUGIN_SKILL_INVALID:$name"
                null
            }
        }
    }

    fun servers(
        files: Map<String, ByteArray>,
        diagnostics: MutableList<String>,
    ): Map<String, JsonObject> {
        val bytes = files["mcp.json"]
        if (bytes == null) {
            if (files.keys.any { it.startsWith("mcp.json/") }) diagnostics += "PLUGIN_MCP_NOT_FILE"
            return emptyMap()
        }
        val servers = serverEntries(bytes, diagnostics)
        require(servers.size <= MAX_SERVERS) { "CONNECTOR_TOO_MANY_SERVERS" }
        return servers.entries
            .mapIndexedNotNull { index, (name, value) ->
                try {
                    // Server labels are not plugin names. This bound is local display/storage policy.
                    require(name.length in 1..128 && name.none { it.code < 0x20 })
                    val config = value as? JsonObject ?: throw IllegalArgumentException("PLUGIN_MCP_SERVER_OBJECT")
                    validateServer(config)
                    name to config
                } catch (_: IllegalArgumentException) {
                    // Raw keys may contain control characters or credentials. Do not echo them on error.
                    diagnostics += "PLUGIN_MCP_SERVER_INVALID:$index"
                    null
                }
            }.toMap()
    }

    private fun serverEntries(
        bytes: ByteArray,
        diagnostics: MutableList<String>,
    ): JsonObject =
        try {
            val root = PluginJson.parse(bytes)
            require(root.keys == setOf("\$schema", "mcpServers"))
            require(PluginJson.string(root, "\$schema") == MCP_SCHEMA)
            root["mcpServers"] as? JsonObject ?: throw IllegalArgumentException("PLUGIN_MCP_OBJECT")
        } catch (_: IllegalArgumentException) {
            diagnostics += "PLUGIN_MCP_CONFIG_INVALID"
            JsonObject(emptyMap())
        }

    private fun validateServer(config: JsonObject) {
        when (PluginJson.string(config, "type")) {
            "stdio" -> validateStdio(config)
            "streamable-http", "sse" -> validateRemote(config)
            else -> throw IllegalArgumentException("PLUGIN_MCP_TRANSPORT")
        }
    }

    private fun validateStdio(config: JsonObject) {
        require(config.keys.all { it in setOf("type", "command", "args", "env", "cwd") })
        val command = PluginJson.string(config, "command")
        require(command.isNotBlank() && command.none { it.isWhitespace() || it.code < 32 } && '$' !in command)
        require(if (command.startsWith("./")) contained(command.removePrefix("./")) else command.none { it in "/\\:" })
        if ("args" in config) {
            val args = config["args"] as? JsonArray ?: throw IllegalArgumentException("PLUGIN_MCP_ARGS")
            require(args.all { it is JsonPrimitive && it.isString })
        }
        if ("env" in config) {
            val env = stringMap(config, "env")
            require(env.keys.none { it.equals("PLUGIN_ROOT", true) || it.equals("PLUGIN_DATA", true) })
        }
        if ("cwd" in config) {
            val cwd = PluginJson.string(config, "cwd")
            val roots = listOf("./", "\${PLUGIN_ROOT}", "\${PLUGIN_DATA}")
            val prefix = roots.firstOrNull { cwd == it || cwd.startsWith(if (it == "./") it else "$it/") }
            require(prefix != null && contained(cwd.removePrefix(prefix).removePrefix("/")))
        }
    }

    private fun validateRemote(config: JsonObject) {
        require(config.keys.all { it in setOf("type", "url", "headers") })
        val url = PluginJson.string(config, "url")
        val uri =
            try {
                URI(url)
            } catch (invalid: URISyntaxException) {
                throw IllegalArgumentException("PLUGIN_MCP_URL", invalid)
            }
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawFragment == null)
        require(uri.scheme == "https" || loopback(uri.host))
        if ("headers" in config) {
            val headers = stringMap(config, "headers")
            require(
                headers.keys
                    .map { it.lowercase() }
                    .distinct()
                    .size == headers.size,
            )
            require(
                headers.all { (key, value) ->
                    headerToken.matches(key) && value.all { it == '\t' || it.code in 32..126 || it.code in 128..255 }
                },
            )
        }
    }

    private fun contained(path: String): Boolean =
        !path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it == ".." }

    private fun stringMap(
        config: JsonObject,
        key: String,
    ): Map<String, String> {
        val values = config[key] as? JsonObject ?: throw IllegalArgumentException("PLUGIN_MCP_MAP")
        return values.mapValues { (_, value) ->
            require(value is JsonPrimitive && value.isString)
            value.content
        }
    }

    private fun loopback(host: String): Boolean {
        if (host == "localhost" || host in setOf("[::1]", "::1", "[0:0:0:0:0:0:0:1]")) return true
        val numbers = host.split('.')
        return numbers.size == 4 && numbers.first() == "127" &&
            numbers.all { part ->
                part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255
            }
    }
}

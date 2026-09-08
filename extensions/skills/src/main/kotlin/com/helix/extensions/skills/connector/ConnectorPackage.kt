package com.helix.extensions.skills.connector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.apache.commons.compress.archivers.zip.ZipFile
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

/** Portable data only. Foreign manifests never supply executable hooks or authority. */
data class ConnectorEndpoint(
    val name: String,
    val url: String,
    val needsCredential: Boolean,
)

data class ConnectorSkill(
    val directory: String,
    val files: Map<String, ByteArray>,
)

data class ConnectorPackage(
    val name: String,
    val source: String,
    val contentHash: String,
    val endpoints: List<ConnectorEndpoint>,
    val skills: List<ConnectorSkill>,
    val diagnostics: List<String>,
)

/** Reads a bounded archive without extracting any foreign paths or running setup code. */
@Suppress("TooManyFunctions", "MagicNumber")
class ConnectorPackageReader {
    @Suppress("NestedBlockDepth") // archive/resource scopes enclose per-entry bounds
    fun readZip(path: Path): ConnectorPackage {
        require(
            java.nio.file.Files
                .size(path) <= MAX_BYTES,
        ) { "CONNECTOR_TOO_LARGE" }
        val files = linkedMapOf<String, ByteArray>()
        var total = 0
        var count = 0
        ZipFile.builder().setPath(path).get().use { zip ->
            val entries = zip.entries
            val seen = mutableSetOf<String>()
            while (entries.hasMoreElements()) {
                require(++count <= 1024) { "CONNECTOR_TOO_MANY_FILES" }
                val entry = entries.nextElement()
                val name = safePath(entry.name.removeSuffix("/"))
                require(seen.add(name)) { "CONNECTOR_DUPLICATE_PATH" }
                val type = entry.unixMode and 0xF000
                require(type == 0 || type == 0x8000 || type == 0x4000) { "CONNECTOR_SPECIAL_FILE" }
                if (!entry.isDirectory) {
                    val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_FILE_BYTES) }
                    total += bytes.size
                    require(total <= MAX_BYTES) { "CONNECTOR_TOO_LARGE" }
                    require(bytes.size.toLong() <= entry.compressedSize.coerceAtLeast(1) * 100) {
                        "CONNECTOR_COMPRESSION_RATIO"
                    }
                    files[name] = bytes
                }
            }
        }
        return parse(unwrap(files))
    }

    fun readJson(bytes: ByteArray): ConnectorPackage {
        require(bytes.size <= MAX_FILE_BYTES) { "CONNECTOR_TOO_LARGE" }
        return parse(mapOf("mcp.json" to bytes))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // explicit foreign-format branches, each independently diagnosed
    fun parse(files: Map<String, ByteArray>): ConnectorPackage {
        require(files.size <= 1024 && files.values.sumOf { it.size.toLong() } <= MAX_BYTES) { "CONNECTOR_TOO_LARGE" }
        files.forEach { (path, bytes) ->
            safePath(path)
            require(bytes.size <= MAX_FILE_BYTES) { "CONNECTOR_TOO_LARGE" }
        }
        val manifests = MANIFESTS.filter { it in files }
        require(manifests.size <= 1) { "CONNECTOR_AMBIGUOUS_MANIFEST" }
        val manifestPath = manifests.singleOrNull()
        val manifest = manifestPath?.let { json(files.getValue(it)) } ?: JsonObject(emptyMap())
        val source = manifestPath?.substringBefore('/') ?: "MCP_SKILLS_EXPORT"
        val name = manifest.string("name") ?: "imported-connector"
        require(NAME.matches(name)) { "CONNECTOR_INVALID_NAME" }
        val diagnostics = mutableListOf<String>()
        UNSUPPORTED.forEach { field ->
            if (field in manifest || files.keys.any { it == field || it.startsWith("$field/") }) {
                diagnostics += "UNSUPPORTED_${field.uppercase()}"
            }
        }
        if (".app.json" in files || "apps" in manifest) diagnostics += "HOST_APP_REQUIRES_NEW_CONNECTION"
        if (files.keys.any { it.endsWith(".toml") }) diagnostics += "TOML_REQUIRES_MCP_JSON_EXPORT"
        val configs = linkedMapOf<String, JsonObject>()
        val defaults =
            files.keys.filter {
                it in setOf(".mcp.json", "mcp.json", "qwenwork-mcp.json") ||
                    ('/' !in it && it.startsWith("qwenwork-mcp-") && it.endsWith(".json"))
            }
        defaults.forEach { configs[it] = json(files.getValue(it)) }
        when (val reference = manifest["mcpServers"]) {
            is JsonObject -> {
                configs["inline"] = reference
            }

            is JsonPrimitive -> {
                val path = safePath(reference.content.removePrefix("./"))
                configs[path] = json(requireNotNull(files[path]) { "CONNECTOR_MISSING_MCP_CONFIG" })
            }

            null -> {
                Unit
            }

            else -> {
                diagnostics.add("UNSUPPORTED_MCP_CONFIG_REFERENCE")
            }
        }
        files.keys.filter { '/' !in it && it.endsWith(".json") && "mcp" in it.lowercase() && it !in configs }.forEach {
            diagnostics += "UNRECOGNIZED_MCP_CONFIG:$it"
        }
        val servers = linkedMapOf<String, JsonObject>()
        configs.values.forEach { config ->
            val wrapped = serverMap(config, diagnostics)
            require(wrapped is JsonObject) { "CONNECTOR_INVALID_MCP_CONFIG" }
            wrapped.forEach { (id, value) ->
                require(NAME.matches(id) && value is JsonObject) { "CONNECTOR_INVALID_SERVER" }
                require(id !in servers || servers[id] == value) { "CONNECTOR_DUPLICATE_SERVER" }
                servers[id] = value
            }
        }
        require(servers.size <= 32) { "CONNECTOR_TOO_MANY_SERVERS" }
        val endpoints = servers.mapNotNull { (id, config) -> endpoint(id, config, diagnostics) }
        val skills = skillFiles(files, manifest, diagnostics)
        require(endpoints.isNotEmpty() || skills.isNotEmpty() || diagnostics.isNotEmpty()) { "CONNECTOR_EMPTY" }
        return ConnectorPackage(name, source, digest(files), endpoints, skills, diagnostics.distinct())
    }

    @Suppress("ReturnCount") // unsupported transports and endpoints are distinct migration outcomes
    private fun endpoint(
        id: String,
        config: JsonObject,
        diagnostics: MutableList<String>,
    ): ConnectorEndpoint? {
        val transport = config.string("type") ?: config.string("transport")
        if ("command" in config || transport == "stdio") {
            diagnostics += "STDIO_REQUIRES_ANDROID_RUNTIME:$id"
            return null
        }
        if (transport != null && transport !in setOf("http", "streamable-http", "streamable_http", "streamableHttp")) {
            diagnostics += "UNSUPPORTED_TRANSPORT:$id"
            return null
        }
        val url = config.string("url")
        if (url == null || !portableUrl(url)) {
            diagnostics += "ENDPOINT_REQUIRES_CONFIGURATION:$id"
            return null
        }
        val auth = config.keys.any { it in AUTH_FIELDS }
        if (auth) diagnostics += "AUTH_REQUIRES_CONFIGURATION:$id"
        if (config.keys.any { it !in SERVER_FIELDS }) diagnostics += "UNSUPPORTED_SERVER_OPTIONS:$id"
        return ConnectorEndpoint(id, url, auth)
    }

    private fun skillFiles(
        files: Map<String, ByteArray>,
        manifest: JsonObject,
        diagnostics: MutableList<String>,
    ): List<ConnectorSkill> {
        val declared = manifest["skills"]
        val roots = mutableListOf("skills")
        if (declared is JsonPrimitive) roots += safePath(declared.content.removePrefix("./").removeSuffix("/"))
        if (declared != null && declared !is JsonPrimitive) diagnostics += "UNSUPPORTED_SKILL_REFERENCE"
        val skillRoots =
            files.keys
                .filter { it.endsWith("/SKILL.md") }
                .map { it.removeSuffix("/SKILL.md") }
                .filter { path -> roots.any { path == it || path.startsWith("$it/") } || manifest.isEmpty() }
        require(skillRoots.size <= 64) { "CONNECTOR_TOO_MANY_SKILLS" }
        val names = mutableSetOf<String>()
        return skillRoots.map { root ->
            require(skillRoots.none { it != root && it.startsWith("$root/") }) { "CONNECTOR_NESTED_SKILL" }
            val name = root.substringAfterLast('/')
            val content =
                files
                    .filterKeys { it.startsWith("$root/") }
                    .mapKeys { (path, _) -> path.removePrefix("$root/") }
                    .mapValues { it.value.copyOf() }
            val adapted = ConnectorSkillMetadataAdapter.adapt(content, diagnostics, name)
            val declaredName =
                com.helix.extensions.skills
                    .SkillLoader()
                    .importFrontmatter(adapted.getValue("SKILL.md"))
                    .first["name"] as? String
            val directory = declaredName ?: name
            require('/' !in safePath(directory)) { "CONNECTOR_INVALID_SKILL_NAME" }
            require(names.add(directory)) { "CONNECTOR_DUPLICATE_SKILL_NAME" }
            return@map ConnectorSkill(directory, adapted)
        }
    }

    @Suppress("ReturnCount") // unknown versions and known envelopes have distinct import outcomes
    private fun serverMap(
        config: JsonObject,
        diagnostics: MutableList<String>,
    ): JsonObject {
        if ("schemaVersion" in config) {
            if (config.string("schemaVersion") != "qwenwork.mcp/v1") {
                diagnostics += "UNSUPPORTED_MCP_SCHEMA"
                return JsonObject(emptyMap())
            }
            val dynamic = config["dynamic"] as? JsonObject
            require(dynamic != null) { "CONNECTOR_INVALID_QWENWORK_CONFIG" }
            diagnostics += "QWENWORK_CONFIG_SNAPSHOT"
            return dynamic["servers"] as? JsonObject ?: error("CONNECTOR_INVALID_QWENWORK_SERVERS")
        }
        return (config["mcpServers"] ?: config["mcp_servers"] ?: config) as? JsonObject
            ?: error("CONNECTOR_INVALID_MCP_CONFIG")
    }

    private fun portableUrl(value: String): Boolean =
        runCatching {
            val uri = URI(value)
            value.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                '$' !in value && '{' !in value && '}' !in value
        }.getOrDefault(false)

    private fun unwrap(files: Map<String, ByteArray>): Map<String, ByteArray> {
        if (MANIFESTS.any { it in files } || files.keys.any { '/' !in it }) return files
        val roots = files.keys.map { it.substringBefore('/') }.distinct()
        return if (roots.size == 1) files.mapKeys { it.key.substringAfter('/') } else files
    }

    private fun json(bytes: ByteArray): JsonObject {
        require(bytes.size <= 256 * 1024) { "CONNECTOR_CONFIG_TOO_LARGE" }
        return try {
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString()
            requireJsonDepth(text)
            Json.parseToJsonElement(text) as? JsonObject
                ?: error("CONNECTOR_INVALID_JSON")
        } catch (_: IllegalArgumentException) {
            error("CONNECTOR_INVALID_JSON")
        }
    }

    private fun requireJsonDepth(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            when {
                escaped -> {
                    escaped = false
                }

                quoted && char == '\\' -> {
                    escaped = true
                }

                char == '"' -> {
                    quoted = !quoted
                }

                !quoted && (char == '{' || char == '[') -> {
                    depth++
                    require(depth <= 32) { "CONNECTOR_JSON_DEPTH" }
                }

                !quoted && (char == '}' || char == ']') -> {
                    depth--
                }
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private val MANIFESTS =
            listOf(
                ".codex-plugin/plugin.json",
                ".claude-plugin/plugin.json",
                ".codebuddy-plugin/plugin.json",
                "connector.json",
            )
        private val NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}")
        private val UNSUPPORTED = listOf("hooks", "agents", "commands", "rules", "workflows", "mcp", "dependencies")
        private val AUTH_FIELDS =
            setOf(
                "headers",
                "staticHeaders",
                "http_headers",
                "env_http_headers",
                "bearer_token_env_var",
                "oauth",
                "auth",
            )
        private val SERVER_FIELDS =
            AUTH_FIELDS + setOf("url", "type", "transport", "disabled", "enabled", "timeout", "defer_loading")

        fun safePath(path: String): String {
            require(
                path.length in 1..512 && '\\' !in path && ':' !in path &&
                    path.none {
                        it.code < 32
                    },
            ) { "CONNECTOR_INVALID_PATH" }
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "CONNECTOR_PATH_TRAVERSAL" }
            return path
        }

        fun readBounded(
            input: java.io.InputStream,
            limit: Int,
        ): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                require(output.size() + count <= limit) { "CONNECTOR_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
        }

        fun digest(files: Map<String, ByteArray>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            files.toSortedMap().forEach { (name, bytes) ->
                digest.update(name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

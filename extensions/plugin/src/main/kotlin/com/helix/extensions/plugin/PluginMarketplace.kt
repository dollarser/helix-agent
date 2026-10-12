package com.helix.extensions.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

data class MarketplacePluginEntry(
    val name: String,
    val description: String,
    val version: String?,
    val bundle: PluginPackage?,
    val issue: String?,
)

data class PluginMarketplace(
    val name: String,
    val format: String,
    val contentHash: String,
    val entries: List<MarketplacePluginEntry>,
) {
    val identity: String get() = "$format:$name"
}

/** A marketplace describes candidates, never an installation or a network authorization. */
class PluginMarketplaceReader {
    fun readZip(path: Path): PluginMarketplace = parse(PluginPackageReader().readArchiveFiles(path))

    fun readJson(bytes: ByteArray): PluginMarketplace = parse(mapOf("marketplace.json" to bytes))

    fun readDirectory(
        path: Path,
        cancelled: () -> Boolean = { false },
    ): PluginMarketplace = parse(PluginDirectorySnapshot.read(path, cancelled))

    fun parse(files: Map<String, ByteArray>): PluginMarketplace {
        PluginPackageReader.validateFiles(files)
        val indexes = INDEX_PATHS.filter { it in files }
        require(indexes.isNotEmpty()) { "MARKETPLACE_INDEX_MISSING" }
        val roots = indexes.map { PluginJson.parse(files.getValue(it)) }
        require(roots.distinct().size == 1) { "MARKETPLACE_AMBIGUOUS_INDEX" }
        val root = roots.first()
        val plugins = root["plugins"] as? JsonArray ?: throw IllegalArgumentException("MARKETPLACE_PLUGINS_REQUIRED")
        require(plugins.size in 1..128) { "MARKETPLACE_ENTRY_COUNT" }
        val raw = plugins.map { it as? JsonObject ?: throw IllegalArgumentException("MARKETPLACE_INVALID_ENTRY") }
        val name = string(root, "name") ?: raw.singleOrNull()?.let { string(it, "name") }?.let { "$it-marketplace" }
        require(name != null && NAME.matches(name)) { "MARKETPLACE_NAME_REQUIRED" }
        val names = mutableSetOf<String>()
        val entries =
            raw.map { entry ->
                val entryName = requireNotNull(string(entry, "name")) { "MARKETPLACE_ENTRY_NAME_REQUIRED" }
                require(NAME.matches(entryName) && names.add(entryName)) { "MARKETPLACE_DUPLICATE_OR_INVALID_ENTRY" }
                candidate(files, entryName, entry)
            }
        return PluginMarketplace(name, indexes.first().substringBefore('/'), PluginPackageReader.digest(files), entries)
    }

    @Suppress("ReturnCount") // Remote metadata, absent content and validated content are distinct outcomes.
    private fun candidate(
        files: Map<String, ByteArray>,
        name: String,
        entry: JsonObject,
    ): MarketplacePluginEntry {
        val description = string(entry, "description").orEmpty().take(2048)
        val version = string(entry, "version")?.take(128)
        val source = string(entry, "source")
        if (source == null || !source.startsWith("./")) {
            return MarketplacePluginEntry(name, description, version, null, "MARKETPLACE_SOURCE_NOT_BUNDLED")
        }
        val path = source.removePrefix("./").removeSuffix("/")
        if (path.isNotEmpty()) PluginPackageReader.safePath(path)
        val subtree =
            files
                .filterKeys { path.isEmpty() || it.startsWith("$path/") }
                .mapKeys { (key, _) -> if (path.isEmpty()) key else key.removePrefix("$path/") }
        if (subtree.isEmpty()) {
            return MarketplacePluginEntry(
                name,
                description,
                version,
                null,
                "MARKETPLACE_SOURCE_MISSING",
            )
        }
        val candidate = subtree.toMutableMap()
        if (entry["strict"] == JsonPrimitive(false) && MANIFESTS.none { it in candidate }) {
            candidate[".claude-plugin/plugin.json"] = entry.toString().toByteArray(Charsets.UTF_8)
        }
        return try {
            val bundle = PluginPackageReader().parse(candidate)
            require(bundle.name == name) { "MARKETPLACE_PLUGIN_NAME_MISMATCH" }
            MarketplacePluginEntry(name, description, bundle.versionLabel ?: version, bundle, null)
        } catch (invalid: IllegalArgumentException) {
            MarketplacePluginEntry(name, description, version, null, invalidCode(invalid))
        } catch (invalid: IllegalStateException) {
            MarketplacePluginEntry(name, description, version, null, invalidCode(invalid))
        }
    }

    /** Decoder messages can contain source bytes/credentials; expose only a known validation code. */
    private fun invalidCode(failure: RuntimeException): String =
        if (failure.message == "MARKETPLACE_PLUGIN_NAME_MISMATCH") {
            "MARKETPLACE_PLUGIN_NAME_MISMATCH"
        } else {
            "MARKETPLACE_PLUGIN_INVALID"
        }

    private fun string(
        root: JsonObject,
        key: String,
    ): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        val INDEX_PATHS =
            listOf(
                "marketplace.json",
                ".codex-plugin/marketplace.json",
                ".claude-plugin/marketplace.json",
                ".codebuddy-plugin/marketplace.json",
                ".workbuddy-plugin/marketplace.json",
            )
        private val MANIFESTS =
            listOf(
                "plugin.json",
                ".codex-plugin/plugin.json",
                ".claude-plugin/plugin.json",
                ".codebuddy-plugin/plugin.json",
                ".workbuddy-plugin/plugin.json",
                "connector.json",
            )
        private val NAME = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}")
    }
}

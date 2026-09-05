package com.helix.app.connector

import android.content.Context
import android.net.Uri
import com.helix.app.mcp.McpAppService
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.extensions.mcp.McpHandshakeSnapshot
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillSource
import com.helix.extensions.skills.connector.ConnectorEndpoint
import com.helix.extensions.skills.connector.ConnectorPackage
import com.helix.extensions.skills.connector.ConnectorPackageReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** UI facade: picker contents never enter chat, Room, logging, or foreign setup code. */
@Suppress("TooManyFunctions") // single UI facade for import, connection and component lifecycle
class ConnectorService(
    private val context: Context,
    private val storage: HelixStorage,
    private val mcp: McpAppService,
    private val importer: SkillImportService,
    private val skills: SkillRepository,
) {
    private val root = context.filesDir.toPath().resolve("connectors")
    private val snapshots = context.filesDir.toPath().resolve("skills/snapshots")
    private val reader = ConnectorPackageReader()

    fun preview(uri: Uri): ConnectorPackage {
        val bytes =
            context.contentResolver.openInputStream(uri).use { input ->
                ConnectorPackageReader.readBounded(requireNotNull(input), ConnectorPackageReader.MAX_BYTES)
            }
        if (bytes.firstOrNull() == 'P'.code.toByte() && bytes.getOrNull(1) == 'K'.code.toByte()) {
            val temporary = Files.createTempFile(context.cacheDir.toPath(), "connector-", ".zip")
            try {
                Files.write(temporary, bytes)
                return reader.readZip(temporary)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
        return reader.readJson(bytes)
    }

    @Synchronized
    fun install(bundle: ConnectorPackage): InstalledConnector {
        list().firstOrNull { it.hash == bundle.contentHash }?.let { return it }
        require(bundle.endpoints.isNotEmpty() || bundle.skills.isNotEmpty()) { "CONNECTOR_NO_PORTABLE_COMPONENT" }
        val staging = Files.createTempDirectory(context.cacheDir.toPath(), "connector-skills-")
        try {
            val staged = mutableListOf<com.helix.extensions.skills.StagedSkillImport>()
            try {
                bundle.skills.forEachIndexed { index, skill ->
                    val directory = staging.resolve(index.toString()).resolve(skill.directory)
                    skill.files.forEach { (name, bytes) ->
                        val target = directory.resolve(ConnectorPackageReader.safePath(name))
                        Files.createDirectories(target.parent)
                        Files.write(target, bytes)
                    }
                    staged += importer.stageDirectory(directory)
                }
                val keys = staged.map { item -> skills.registerSnapshot(importer.commit(item, snapshots)) }
                val id = "conn-" + UUID.randomUUID().toString()
                val record =
                    InstalledConnector(
                        id,
                        bundle.name,
                        bundle.source,
                        bundle.contentHash,
                        bundle.endpoints.mapIndexed { index, endpoint -> InstalledEndpoint("$id-$index", endpoint) },
                        keys,
                        bundle.diagnostics,
                    )
                Files.createDirectories(root)
                val temporary = Files.createTempFile(root, ".pending-", ".tmp")
                try {
                    Files.write(temporary, encode(record).toByteArray(Charsets.UTF_8))
                    Files.move(temporary, root.resolve("$id.json"), StandardCopyOption.ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temporary)
                }
                return record
            } finally {
                staged.forEach { importer.discard(it) }
            }
        } finally {
            deleteTree(staging)
        }
    }

    @Synchronized
    fun list(): List<InstalledConnector> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .sorted()
                .map { decode(Files.readAllBytes(it).toString(Charsets.UTF_8)) }
                .collect(
                    java.util.stream.Collectors
                        .toList(),
                )
        }
    }

    /** Registers only on an explicit connection-test action, with a fresh local credential. */
    suspend fun test(
        endpoint: InstalledEndpoint,
        bearer: String,
    ): McpHandshakeSnapshot {
        prepareEndpoint(endpoint, bearer)
        return mcp.testConnection(endpoint.id)
    }

    @Synchronized
    private fun prepareEndpoint(
        endpoint: InstalledEndpoint,
        bearer: String,
    ) {
        requireOwned(endpoint)
        val existing = storage.mcpServers.list().firstOrNull { it.id == endpoint.id }
        val alias = SecretAlias(endpoint.id)
        if (bearer.isNotBlank()) {
            require(
                bearer.length <= 8192 && bearer.none { it.isWhitespace() || it.code < 32 },
            ) { "CONNECTOR_INVALID_CREDENTIAL" }
            storage.secrets.put(alias, bearer)
        }
        if (existing == null) {
            mcp.registerDisabled(
                endpoint.id,
                endpoint.endpoint.url,
                alias.value.takeIf { bearer.isNotBlank() || endpoint.endpoint.needsCredential },
            )
        } else if (bearer.isNotBlank() && existing.authAlias == null) {
            storage.mcpServers.replaceAuthAlias(endpoint.id, alias.value)
        }
        mcp.disable(endpoint.id)
    }

    @Synchronized
    fun enable(
        endpoint: InstalledEndpoint,
        snapshot: McpHandshakeSnapshot,
        selected: Set<String>,
    ) {
        requireOwned(endpoint)
        require(snapshot.serverId.value == endpoint.id) { "CONNECTOR_WRONG_SERVER" }
        mcp.enable(snapshot, selected)
    }

    @Synchronized
    fun disable(endpoint: InstalledEndpoint) {
        requireOwned(endpoint)
        if (storage.mcpServers.list().any { it.id == endpoint.id }) mcp.disable(endpoint.id)
    }

    fun enabled(endpoint: InstalledEndpoint): Boolean = mcp.isActive(endpoint.id)

    fun skillEnabled(key: SkillKey): Boolean = skills.list().any { it.key == key && it.enabled }

    @Synchronized
    fun setSkillEnabled(
        key: SkillKey,
        enabled: Boolean,
    ) {
        require(list().any { key in it.skills }) { "CONNECTOR_UNKNOWN_SKILL" }
        skills.setEnabled(key, enabled, SkillEnablementScope.GLOBAL)
    }

    @Synchronized
    fun remove(record: InstalledConnector) {
        val current = list().single { it.id == record.id }
        current.endpoints.forEach { endpoint ->
            disable(endpoint)
            storage.secrets.delete(SecretAlias(endpoint.id))
        }
        val otherKeys = list().filter { it.id != record.id }.flatMap { it.skills }.toSet()
        current.skills.filter { it !in otherKeys }.forEach { setSkillEnabled(it, false) }
        Files.delete(root.resolve("${current.id}.json"))
    }

    private fun requireOwned(endpoint: InstalledEndpoint) {
        require(list().any { endpoint in it.endpoints }) { "CONNECTOR_UNKNOWN_ENDPOINT" }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}

data class InstalledEndpoint(
    val id: String,
    val endpoint: ConnectorEndpoint,
)

data class InstalledConnector(
    val id: String,
    val name: String,
    val source: String,
    val hash: String,
    val endpoints: List<InstalledEndpoint>,
    val skills: List<SkillKey>,
    val diagnostics: List<String>,
)

private fun encode(record: InstalledConnector): String =
    buildJsonObject {
        put("formatVersion", 1)
        put("id", record.id)
        put("name", record.name)
        put("source", record.source)
        put("hash", record.hash)
        put(
            "endpoints",
            JsonArray(
                record.endpoints.map { server ->
                    buildJsonObject {
                        put("id", server.id)
                        put("name", server.endpoint.name)
                        put("url", server.endpoint.url)
                        put("credential", server.endpoint.needsCredential)
                    }
                },
            ),
        )
        put(
            "skills",
            JsonArray(
                record.skills.map { skill ->
                    buildJsonObject {
                        put("name", skill.name)
                        put("hash", skill.snapshotHash)
                    }
                },
            ),
        )
        put("diagnostics", JsonArray(record.diagnostics.map(::JsonPrimitive)))
    }.toString()

private fun decode(text: String): InstalledConnector {
    val obj = Json.parseToJsonElement(text).jsonObject
    require(obj["formatVersion"]?.jsonPrimitive?.content == "1") { "CONNECTOR_RECORD_VERSION" }

    fun value(key: String) = obj.getValue(key).jsonPrimitive.content
    return InstalledConnector(
        value("id"),
        value("name"),
        value("source"),
        value("hash"),
        obj.getValue("endpoints").jsonArray.map { item ->
            val e = item.jsonObject
            InstalledEndpoint(
                e.getValue("id").jsonPrimitive.content,
                ConnectorEndpoint(
                    e.getValue("name").jsonPrimitive.content,
                    e.getValue("url").jsonPrimitive.content,
                    e.getValue("credential").jsonPrimitive.content == "true",
                ),
            )
        },
        obj.getValue("skills").jsonArray.map { item ->
            val s = item.jsonObject
            SkillKey(
                SkillSource.USER_IMPORTED,
                s.getValue("name").jsonPrimitive.content,
                s.getValue("hash").jsonPrimitive.content,
            )
        },
        obj.getValue("diagnostics").jsonArray.map { it.jsonPrimitive.content },
    )
}
